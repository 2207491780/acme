package com.acme.framework.workflow.support;

import com.acme.common.core.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * ServiceTask 委托接收端点（REQ-20261122-233425 确认②②；Spring Boot 自动装配——业务侧 pom 引入
 * framework-workflow 即生效）：POST {@code acme.workflow.service-task.receive-path-prefix}
 * （缺省 /internal/v1/service-tasks）/{*尾段} 收冻结字段名协议体 → 按 serviceRef 路由 handler →
 * handler 结果变量 → 自动回调引擎 internal callback（X-Acme-Token sub=业务服务名）→ 回传结果。
 *
 * <p>处理模式（{@code acme.workflow.service-task.receive-async}）：同步（缺省）=handler+回调
 * 内联下发请求线程（失败经 5xx 反噬引擎委托语义 → 重试/挂起）；异步=受理即 2xx，handler 与
 * 回调经调度器延时执行（聚合形态与引擎同 JVM 时业务状态机跃迁可观测，handler 异常不反噬
 * 下发、仅告警——使用方按形态取舍）。
 * internal 面语义：不经网关（REQ-054613 先例，Result 信封）；下发请求携 workflow 签发的
 * X-Acme-Token（sub=platform-workflow），经 InternalTokenAuthFilter 验签绑定上下文。
 * handler 未注册 → {@link WorkflowServiceTaskError#HANDLER_NOT_FOUND}（404 语义明确，AC-1）。</p>
 */
@RestController
public class WorkflowServiceTaskReceiveController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowServiceTaskReceiveController.class);

    private final WorkflowServiceTaskHandlerRegistry registry;
    private final WorkflowServiceTaskCallbackClient callbackClient;
    private final boolean receiveAsync;
    private final long initialDelayMs;
    private final long callbackDelayMs;
    private final ScheduledExecutorService asyncExecutor;

    public WorkflowServiceTaskReceiveController(WorkflowServiceTaskHandlerRegistry registry,
                                                WorkflowServiceTaskCallbackClient callbackClient,
                                                boolean receiveAsync, long initialDelayMs,
                                                long callbackDelayMs) {
        this.registry = registry;
        this.callbackClient = callbackClient;
        this.receiveAsync = receiveAsync;
        this.initialDelayMs = Math.max(0L, initialDelayMs);
        this.callbackDelayMs = Math.max(0L, callbackDelayMs);
        this.asyncExecutor = receiveAsync ? newScheduledExecutor() : null;
    }

    /** 委托受理（路径尾段不参与路由——路由键=body.serviceRef；engine 重试 attempt 仅透传）。 */
    @PostMapping("${acme.workflow.service-task.receive-path-prefix:/internal/v1/service-tasks}/**")
    public Result<Map<String, Object>> receive(@RequestBody ServiceTaskDispatchPayload payload) {
        WorkflowServiceTaskHandler handler = registry.require(payload.serviceRef());
        if (!receiveAsync) {
            Map<String, Object> result = handler.handle(payload);
            callbackClient.callback(payload, result);
            return Result.ok(result);
        }
        // 异步模式：受理即 2xx；handler 与回调经调度器延时执行（失败告警不反噬下发）
        asyncExecutor.schedule(() -> processAsync(handler, payload), initialDelayMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        return Result.ok(Map.of("accepted", Boolean.TRUE));
    }

    private void processAsync(WorkflowServiceTaskHandler handler, ServiceTaskDispatchPayload payload) {
        // 异步线程上下文回放（下发请求过滤链绑定随响应清理——租户自 payload 显式恢复，BR-025 语义）
        com.acme.common.context.TenantContext.bind(
                new com.acme.common.context.TenantContext(payload.tenantId()));
        try {
            Map<String, Object> result = handler.handle(payload);
            Runnable callback = () -> {
                try {
                    callbackClient.callback(payload, result);
                } catch (RuntimeException ex) {
                    log.warn("ServiceTask 委托回调失败（异步模式不反噬下发）：executionId={} nodeId={}",
                            payload.executionId(), payload.nodeId(), ex);
                }
            };
            if (callbackDelayMs > 0) {
                asyncExecutor.schedule(callback, callbackDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                callback.run();
            }
        } catch (RuntimeException ex) {
            log.warn("ServiceTask 委托处理失败（异步模式不反噬下发）：executionId={} nodeId={}",
                    payload.executionId(), payload.nodeId(), ex);
        } finally {
            com.acme.common.context.TenantContext.clear();
        }
    }

    private static ScheduledExecutorService newScheduledExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "acme-workflow-sdk-receive");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
