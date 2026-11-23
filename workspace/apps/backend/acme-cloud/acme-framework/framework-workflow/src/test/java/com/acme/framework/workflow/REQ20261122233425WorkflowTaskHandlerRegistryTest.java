package com.acme.framework.workflow;

import com.acme.common.core.BizException;
import com.acme.framework.workflow.support.ServiceTaskDispatchPayload;
import com.acme.framework.workflow.support.WorkflowServiceTaskError;
import com.acme.framework.workflow.support.WorkflowServiceTaskHandler;
import com.acme.framework.workflow.support.WorkflowServiceTaskHandlerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261122-233425（BPM-5）framework-workflow SDK handler 注册表路由单测
 * （契约确认②；AC-1 承载：WorkflowServiceTaskHandler SPI + 注册表路由——注册命中 /
 * 未注册明确语义 404；AC-9 运行侧契约锚）。
 *
 * <p>【等实现标注解除记录】backend 已交付 support/ 三类与注册表（2026-11-22 工作树核对），
 * 本类改为直引断言；类名/包名漂移如再现，红=披露差异 TL 裁决。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 注册表构造形态=List 收集构造（backend 交付形态）；空表构造=无 handler 装配；
 * A-2 未注册语义双层承载：注册表 require → BizException(HANDLER_NOT_FOUND=SYS-13-0001，
 *     HttpStatusAware 404)；接收端点同码 404——本类钉注册表层，端点级 404 由接收端点
 *     状态码语义（HANDLER_NOT_FOUND httpStatus=404）组合承载；
 * A-3 SDK 消费侧载荷=framework-workflow 镜像 record（framework 层禁依赖服务模块，4.4.4
 *     enforcer；字段名/顺序逐字对齐 workflow-api 冻结 DTO——镜像锁定由 bootstrap 侧
 *     Mirror 用例承载，本类只钉路由语义）。</p>
 */
class REQ20261122233425WorkflowTaskHandlerRegistryTest {

    private static final ServiceTaskDispatchPayload PAYLOAD = new ServiceTaskDispatchPayload(
            1L, 2L, "bk-233425", "default", "filing", "leave-service",
            "/internal/workflow/leave/filing", Map.of("leaveType", "annual"),
            "trace-233425", 0);

    // ---------- AC-1：注册表路由（注册命中） ----------

    @Test
    void should_route_dispatch_payload_to_registered_handler_when_service_ref_matches() {
        RecordingHandler handler = new RecordingHandler("leave-service",
                Map.of("filed", true));
        WorkflowServiceTaskHandlerRegistry registry =
                new WorkflowServiceTaskHandlerRegistry(List.of(handler));

        // find（查询视图）：Optional 命中同实例
        Optional<WorkflowServiceTaskHandler> found = registry.find("leave-service");
        assertThat(found).as("已注册 serviceRef 应命中（find 查询视图）").isPresent();
        assertThat(found.get()).isSameAs(handler);

        // require（路由视图）：同实例
        assertThat(registry.require("leave-service"))
                .as("已注册 serviceRef 应路由到同一 handler（require 路由视图）")
                .isSameAs(handler);

        // 载荷触达：handle(冻结字段域镜像 record) → 结果变量回传（确认②「handler 返回结果变量」）
        Map<String, Object> returned = handler.handle(PAYLOAD);
        assertThat(returned).as("handler 结果变量应回传（SDK 自动回调合并用）")
                .containsEntry("filed", true);
        assertThat(handler.captured()).as("下发协议体应原样触达 handler（镜像 record 解码）")
                .isSameAs(PAYLOAD);

        // 注册表视图：serviceRef 集/数量
        assertThat(registry.serviceRefs()).containsExactly("leave-service");
        assertThat(registry.size()).isEqualTo(1);
    }

    // ---------- AC-1：未注册明确语义（HANDLER_NOT_FOUND → 404） ----------

    @Test
    void should_fail_with_handler_not_found_404_semantics_when_service_ref_not_registered() {
        WorkflowServiceTaskHandlerRegistry registry =
                new WorkflowServiceTaskHandlerRegistry(List.of(
                        new RecordingHandler("leave-service", Map.of("filed", true))));

        // find：未注册 → Optional.empty（查询视图不抛）
        assertThat(registry.find("ghost-service"))
                .as("find 查询视图：未注册应为空").isEmpty();

        // require（路由视图）：未注册 → 显式失败 HANDLER_NOT_FOUND（404 语义明确，AC-1）
        assertThatThrownBy(() -> registry.require("ghost-service"))
                .as("未注册 serviceRef 路由应显式失败（不得静默/不得 NPE）")
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(WorkflowServiceTaskError.HANDLER_NOT_FOUND));
        assertThat(WorkflowServiceTaskError.HANDLER_NOT_FOUND.httpStatus())
                .as("HANDLER_NOT_FOUND 应承载 404 语义状态码（AC-1）").isEqualTo(404);
    }

    // ---------- 边界：重复注册显式失败（framework-job 同口径先例） ----------

    @Test
    void should_refuse_duplicate_registration_when_same_service_ref_registered_twice() {
        WorkflowServiceTaskHandler first = new RecordingHandler("leave-service",
                Map.of("filed", true));
        WorkflowServiceTaskHandler second = new RecordingHandler("leave-service",
                Map.of("filed", false));

        assertThatThrownBy(() -> new WorkflowServiceTaskHandlerRegistry(List.of(first, second)))
                .as("同 serviceRef 重复注册应 fail-fast（注册表口径）")
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- 测试桩 ----------

    /** SPI 桩：serviceRef 绑定 + 载荷记录 + 结果变量回传。 */
    private static final class RecordingHandler implements WorkflowServiceTaskHandler {
        private final String serviceRef;
        private final Map<String, Object> resultVars;
        private ServiceTaskDispatchPayload captured;

        private RecordingHandler(String serviceRef, Map<String, Object> resultVars) {
            this.serviceRef = serviceRef;
            this.resultVars = resultVars;
        }

        @Override
        public String serviceRef() {
            return serviceRef;
        }

        @Override
        public Map<String, Object> handle(ServiceTaskDispatchPayload payload) {
            this.captured = payload;
            return resultVars;
        }

        private ServiceTaskDispatchPayload captured() {
            return captured;
        }
    }
}
