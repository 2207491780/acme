package com.acme.platform.workflow.api;

import com.acme.platform.workflow.api.dto.InternalProcessInstanceStartRequest;
import com.acme.platform.workflow.api.dto.WorkflowInstanceDetailView;
import com.acme.platform.workflow.api.dto.WorkflowVariablesWriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowRuntimeClient 契约形状核对（REQ-20261122-233425 确认②③/AC-9）：api 模块 Feign 接口
 * 三方法与 platform-workflow-service internal 端点逐字一致（SDK 消费侧冻结——路径/HTTP 动词/
 * body 形参注解锁定；internal 端点面由 service 模块集成测试承载同路径断言）。
 */
class WorkflowRuntimeClientContractTest {

    @Test
    void feignClientTargetsPlatformWorkflowInternalFace() {
        FeignClient feignClient = WorkflowRuntimeClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("platform-workflow");
        assertThat(feignClient.contextId()).isEqualTo("workflowRuntimeClient");
    }

    @Test
    void declaresExactlyTheThreeInternalRuntimeMethods() {
        assertThat(List.of(WorkflowRuntimeClient.class.getDeclaredMethods())
                .stream().map(Method::getName).sorted().toList())
                .containsExactly("processInstanceDetail", "startProcessInstance", "writeVariables");
    }

    @Test
    void startMatchesInternalEndpointVerbAndPath() throws Exception {
        Method start = WorkflowRuntimeClient.class.getMethod("startProcessInstance",
                InternalProcessInstanceStartRequest.class);
        PostMapping post = start.getAnnotation(PostMapping.class);
        assertThat(post).isNotNull();
        assertThat(post.value()).containsExactly("/internal/v1/workflow/process-instances");
        assertThat(start.getParameterAnnotations()[0])
                .as("启动请求体必须为 @RequestBody（消费侧冻结）")
                .anySatisfy(annotation -> assertThat(annotation).isInstanceOf(RequestBody.class));
        assertThat(start.getReturnType().getSimpleName()).isEqualTo("ContractResult");
    }

    @Test
    void detailMatchesInternalEndpointVerbAndPath() throws Exception {
        Method detail = WorkflowRuntimeClient.class.getMethod("processInstanceDetail", Long.class);
        GetMapping get = detail.getAnnotation(GetMapping.class);
        assertThat(get).isNotNull();
        assertThat(get.value()).containsExactly("/internal/v1/workflow/process-instances/{id}");
        assertThat(detail.getParameterAnnotations()[0])
                .as("路径锚点形参必须为 @PathVariable(\"id\")（消费侧冻结）")
                .anySatisfy(annotation -> {
                    assertThat(annotation).isInstanceOf(org.springframework.web.bind.annotation.PathVariable.class);
                    assertThat(((org.springframework.web.bind.annotation.PathVariable) annotation).value())
                            .isEqualTo("id");
                });
        assertThat(detail.getGenericReturnType().getTypeName())
                .contains(WorkflowInstanceDetailView.class.getSimpleName());
    }

    @Test
    void writeVariablesMatchesInternalEndpointVerbAndPath() throws Exception {
        Method write = WorkflowRuntimeClient.class.getMethod("writeVariables", Long.class,
                WorkflowVariablesWriteRequest.class);
        PutMapping put = write.getAnnotation(PutMapping.class);
        assertThat(put).isNotNull();
        assertThat(put.value()).containsExactly("/internal/v1/workflow/process-instances/{id}/variables");
        assertThat(write.getParameterAnnotations()[1])
                .anySatisfy(annotation -> assertThat(annotation).isInstanceOf(RequestBody.class));
    }
}
