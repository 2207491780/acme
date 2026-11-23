package com.acme.platform.workflow;

import com.acme.platform.workflow.api.WorkflowRuntimeClient;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261122-233425（BPM-5）WorkflowRuntimeClient Feign 契约面核对单测（契约确认②；
 * AC-9 承载）：api 模块 Feign 接口三方法与 workflow internal 端点逐字一致（SDK 消费侧冻结）。
 *
 * <p>契约口径（contracts/REQ-20261122-233425.md 确认②③）：WorkflowRuntimeClient 放
 * platform-workflow-api（对齐跨服务 client 惯例：AuthorizationClient/SchedulerJobClient 先例
 * =api 模块根包 + @FeignClient(name=目标服务, contextId=…)）；三方法
 * startProcessInstance/processInstanceDetail/writeVariables 对齐 internal 3 端点：
 * <ul>
 *   <li>POST /internal/v1/workflow/process-instances（启动）</li>
 *   <li>GET /internal/v1/workflow/process-instances/{id}（详情）</li>
 *   <li>PUT /internal/v1/workflow/process-instances/{id}/variables（增量合并写）</li>
 * </ul>
 * 返回信封=ContractResult（system-api 先例信封；internal 端点 wire 同为 Result 信封族）。</p>
 *
 * <p>【落位说明】委派单口径=framework-workflow 单测，但 framework 层禁依赖服务模块
 * （4.4.4 enforcer；workflow-api 类不在其 classpath）——落位 platform-workflow-service
 * 测试域（消费侧冻结契约的最贴近锚点=服务契约面自身）。tester 交付口径如实披露。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 方法名=契约②钉死三方法名；泛型实参断言面=信封族宽口径（ContractResult/Result 皆容）；
 * A-2 路径断言=逐字对齐（AC-9「逐字一致」钉死）；@PathVariable("id") 绑定名不重复断言
 *     （路径模板含 {id} 即为锚）。</p>
 */
class REQ20261122233425WorkflowRuntimeClientContractTest {

    @Test
    void should_expose_exactly_three_pinned_methods_when_contract_face_inspected() {
        Set<String> methodNames = Arrays.stream(WorkflowRuntimeClient.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(methodNames)
                .as("契约②钉死三方法（多/缺均为契约面漂移，AC-9 冻结口径）")
                .containsExactlyInAnyOrder("startProcessInstance", "processInstanceDetail",
                        "writeVariables");
        assertThat(WorkflowRuntimeClient.class.getDeclaredMethods())
                .as("接口应只声明三方法（SDK 消费侧冻结）").hasSize(3);
    }

    @Test
    void should_map_start_process_instance_to_internal_start_endpoint_when_inspected() {
        Method method = methodOf("startProcessInstance");
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping).as("startProcessInstance 应为 POST 映射").isNotNull();
        assertThat(pathOf(mapping))
                .as("启动路径应逐字对齐 internal 端点（确认③，AC-9）")
                .isEqualTo("/internal/v1/workflow/process-instances");
        assertEnvelopeReturn(method, "startProcessInstance");
    }

    @Test
    void should_map_instance_detail_to_internal_get_endpoint_when_inspected() {
        Method method = methodOf("processInstanceDetail");
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).as("processInstanceDetail 应为 GET 映射").isNotNull();
        assertThat(pathOf(mapping))
                .as("详情路径应逐字对齐 internal 端点（确认③ /{id} 模板，AC-9）")
                .isEqualTo("/internal/v1/workflow/process-instances/{id}");
        assertEnvelopeReturn(method, "processInstanceDetail");
    }

    @Test
    void should_map_write_variables_to_internal_put_endpoint_when_inspected() {
        Method method = methodOf("writeVariables");
        PutMapping mapping = method.getAnnotation(PutMapping.class);
        assertThat(mapping).as("writeVariables 应为 PUT 映射").isNotNull();
        assertThat(pathOf(mapping))
                .as("变量写路径应逐字对齐 internal 端点（确认③ /{id}/variables 模板，AC-9）")
                .isEqualTo("/internal/v1/workflow/process-instances/{id}/variables");
        assertEnvelopeReturn(method, "writeVariables");
    }

    @Test
    void should_declare_platform_workflow_as_feign_target_when_inspected() {
        FeignClient feignClient = WorkflowRuntimeClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).as("应声明 @FeignClient（跨服务 client 惯例）").isNotNull();
        assertThat(feignClient.name())
                .as("Feign 目标服务名应=platform-workflow").isEqualTo("platform-workflow");
        assertThat(feignClient.contextId())
                .as("contextId 应声明（同服务多 client 惯例防冲突）").isNotBlank();
    }

    // ---------- 反射支撑（A-1：泛型实参宽口径） ----------

    private static Method methodOf(String methodName) {
        for (Method method : WorkflowRuntimeClient.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new IllegalStateException("契约方法缺失（AC-9 冻结口径）: " + methodName);
    }

    private static String pathOf(java.lang.annotation.Annotation mapping) {
        if (mapping instanceof PostMapping post) {
            return firstPath(post.path(), post.value());
        }
        if (mapping instanceof GetMapping get) {
            return firstPath(get.path(), get.value());
        }
        if (mapping instanceof PutMapping put) {
            return firstPath(put.path(), put.value());
        }
        throw new IllegalStateException("未知映射注解: " + mapping);
    }

    private static String firstPath(String[] path, String[] value) {
        return Arrays.stream(value)
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .findFirst()
                .orElse("");
    }

    /** A-1：返回类型=信封族（ContractResult/Result 宽容 contains "Result"）。 */
    private static void assertEnvelopeReturn(Method method, String methodName) {
        assertThat(method.getReturnType().getSimpleName())
                .as("%s 返回应为信封族（A-1）：%s", methodName, method.getReturnType())
                .contains("Result");
    }
}
