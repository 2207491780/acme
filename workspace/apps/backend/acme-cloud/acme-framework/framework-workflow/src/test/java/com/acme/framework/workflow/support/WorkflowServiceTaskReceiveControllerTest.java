package com.acme.framework.workflow.support;

import com.acme.common.core.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ServiceTask 委托接收端点切片测试（REQ-20261122-233425 确认②②/AC-1）：POST 缺省前缀收冻结
 * 协议体 → serviceRef 路由 handler → 结果变量回传 + 自动回调一次；handler 未注册 404 语义明确；
 * 回调失败 500 交回引擎委托语义。断言统一 AssertJ（全仓测试断言惯例，不引 hamcrest）。
 */
class WorkflowServiceTaskReceiveControllerTest {

    private static final String DISPATCH_JSON = """
            {"executionId":11,"instanceId":22,"businessKey":"LEAVE-42","tenantId":"default",
             "nodeId":"hr-file","serviceRef":"business-leave","callbackPath":"/internal/v1/service-tasks/leave",
             "variables":{"leaveType":"annual"},"traceId":"trace-1","attempt":0}
            """;

    private final List<ServiceTaskDispatchPayload> handled = new ArrayList<>();
    private final List<Map<String, Object>> callbacks = new ArrayList<>();

    /** 回调客户端桩（记录回调并可控失败——真客户端为 HTTP 面不进切片）。 */
    private final WorkflowServiceTaskCallbackClient callbackClient =
            new WorkflowServiceTaskCallbackClient(null, null, null, null) {
                @Override
                public void callback(ServiceTaskDispatchPayload payload, Map<String, Object> result) {
                    callbacks.add(result);
                }
            };

    private final WorkflowServiceTaskHandlerRegistry registry = new WorkflowServiceTaskHandlerRegistry();

    @BeforeEach
    void setUp() {
        registry.register(new WorkflowServiceTaskHandler() {
            @Override
            public String serviceRef() {
                return "business-leave";
            }

            @Override
            public Map<String, Object> handle(ServiceTaskDispatchPayload payload) {
                handled.add(payload);
                return Map.of("filed", Boolean.TRUE);
            }
        });
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkflowServiceTaskReceiveController(registry, callbackClient, false, 0, 0))
                .setControllerAdvice(new TestAdvice())
                .build();
    }

    private MockMvc mockMvc;

    @Test
    void receivesDispatchRoutesByServiceRefAndCallbacksOnce() throws Exception {
        MvcResult result = mockMvc.perform(dispatch(DISPATCH_JSON)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"success\":true").contains("\"filed\":true");
        assertThat(handled).hasSize(1);
        assertThat(handled.getFirst().businessKey()).isEqualTo("LEAVE-42");
        assertThat(handled.getFirst().executionId()).isEqualTo(11L);
        assertThat(handled.getFirst().serviceRef()).isEqualTo("business-leave");
        // 自动回调（确认②②）：handler 结果变量 → 引擎 internal callback
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks.getFirst()).containsEntry("filed", Boolean.TRUE);
    }

    @Test
    void unknownServiceRefFailsWithExplicit404Semantics() throws Exception {
        MvcResult result = mockMvc.perform(dispatch(
                        DISPATCH_JSON.replace("\"business-leave\"", "\"business-other\""))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("SYS-13-0001");
        assertThat(handled).isEmpty();
        assertThat(callbacks).isEmpty();
    }

    @Test
    void callbackFailureSurfacesAsServerErrorForEngineRetrySemantics() throws Exception {
        WorkflowServiceTaskCallbackClient failing = new WorkflowServiceTaskCallbackClient(null, null, null, null) {
            @Override
            public void callback(ServiceTaskDispatchPayload payload, Map<String, Object> result) {
                throw new BizException(WorkflowServiceTaskError.CALLBACK_FAILED, "连接拒绝");
            }
        };
        MockMvc failingMvc = MockMvcBuilders.standaloneSetup(
                        new WorkflowServiceTaskReceiveController(registry, failing, false, 0, 0))
                .setControllerAdvice(new TestAdvice())
                .build();
        MvcResult result = failingMvc.perform(dispatch(DISPATCH_JSON)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).contains("SYS-13-0002");
        assertThat(handled).hasSize(1);
    }

    private static MockHttpServletRequestBuilder dispatch(String json) {
        return post("/internal/v1/service-tasks/leave")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    /** 测试面错误映射（404/500 语义锚点；生产由 framework-web GlobalExceptionTranslator 承载）。 */
    @ControllerAdvice
    static class TestAdvice {

        @ExceptionHandler(BizException.class)
        org.springframework.http.ResponseEntity<String> handle(BizException ex) {
            int status = ex.errorCode() instanceof com.acme.framework.core.support.HttpStatusAware aware
                    ? aware.httpStatus() : 500;
            return org.springframework.http.ResponseEntity.status(HttpStatus.valueOf(status))
                    .body(ex.errorCode().code());
        }
    }
}
