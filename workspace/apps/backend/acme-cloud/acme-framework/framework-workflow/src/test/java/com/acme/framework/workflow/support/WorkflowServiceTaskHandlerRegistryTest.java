package com.acme.framework.workflow.support;

import com.acme.common.core.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ServiceTask handler 注册表路由单测（REQ-20261122-233425 确认②①/AC-1：serviceRef 路由、
 * 重复注册显式失败、未注册 404 语义明确、冻结字段名解码镜像核对）。
 */
class WorkflowServiceTaskHandlerRegistryTest {

    private static WorkflowServiceTaskHandler handler(String serviceRef) {
        return new WorkflowServiceTaskHandler() {
            @Override
            public String serviceRef() {
                return serviceRef;
            }

            @Override
            public Map<String, Object> handle(ServiceTaskDispatchPayload payload) {
                return Map.of("handled", serviceRef);
            }
        };
    }

    @Test
    void routesByServiceRefAndCollectsAllHandlers() {
        WorkflowServiceTaskHandlerRegistry registry = new WorkflowServiceTaskHandlerRegistry();
        registry.register(handler("business-leave"));
        registry.register(handler("business-expense"));
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.serviceRefs()).containsExactlyInAnyOrder("business-leave", "business-expense");
        assertThat(registry.require("business-leave").serviceRef()).isEqualTo("business-leave");
        assertThat(registry.find("unknown")).isEmpty();
    }

    @Test
    void rejectsDuplicateServiceRefRegistration() {
        WorkflowServiceTaskHandlerRegistry registry = new WorkflowServiceTaskHandlerRegistry();
        registry.register(handler("business-leave"));
        assertThatThrownBy(() -> registry.register(handler("business-leave")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("business-leave");
    }

    @Test
    void requireUnknownServiceRefFailsWithHandlerNotFound404() {
        WorkflowServiceTaskHandlerRegistry registry = new WorkflowServiceTaskHandlerRegistry();
        assertThatThrownBy(() -> registry.require("business-leave"))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(WorkflowServiceTaskError.HANDLER_NOT_FOUND);
                    assertThat(ex.errorCode()).isInstanceOf(com.acme.framework.core.support.HttpStatusAware.class);
                    assertThat(((com.acme.framework.core.support.HttpStatusAware) ex.errorCode()).httpStatus())
                            .isEqualTo(404);
                });
    }

    /** 消费侧解码镜像（冻结契约核对）：字段名与 platform-workflow-api 下发侧逐字一致。 */
    @Test
    void dispatchPayloadDecodesFrozenFieldNames() throws Exception {
        String json = "{\"executionId\":1,\"instanceId\":2,\"businessKey\":\"42\",\"tenantId\":\"default\","
                + "\"nodeId\":\"st1\",\"serviceRef\":\"business-leave\",\"callbackPath\":\"/x\","
                + "\"variables\":{\"a\":1},\"traceId\":\"t-1\",\"attempt\":0}";
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        ServiceTaskDispatchPayload payload = mapper.readValue(json, ServiceTaskDispatchPayload.class);
        assertThat(payload.executionId()).isEqualTo(1L);
        assertThat(payload.instanceId()).isEqualTo(2L);
        assertThat(payload.businessKey()).isEqualTo("42");
        assertThat(payload.tenantId()).isEqualTo("default");
        assertThat(payload.nodeId()).isEqualTo("st1");
        assertThat(payload.serviceRef()).isEqualTo("business-leave");
        assertThat(payload.callbackPath()).isEqualTo("/x");
        assertThat(payload.variables()).containsEntry("a", 1);
        assertThat(payload.traceId()).isEqualTo("t-1");
        assertThat(payload.attempt()).isZero();
    }
}
