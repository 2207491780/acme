package com.acme.platform.infra.internal.audit;

import com.acme.framework.tenant.support.CrossTenantAuditEvent;
import com.acme.framework.tenant.support.CrossTenantAuditSink;
import com.acme.platform.infra.domain.CrossTenantAuditRow;
import com.acme.platform.infra.infrastructure.repository.CrossTenantAuditRowRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 跨租户审计 DB sink（REQ-20261125-001736 AC-3；Q-005 修订：旁路审计异步尽力落库）：事件经
 * 单线程守护执行器异步落 {@code sys_cross_tenant_audit}；写失败 ERROR 告警日志并丢弃该行，
 * 主流程零影响（同步结构化日志是兜底事实源）。
 *
 * <p>异步与写库职责在 sink 实现侧（R3 契约）：accept 仅入队非阻塞；落库线程无租户上下文，
 * 行为平台级数据（无租户列），不触发 TenantWriteGuard。created_at 由 AuditFillListener 填充为
 * 落库时刻（事件 occurredAt 自包含于 SPI 载体，尽力落库容忍亚秒偏差）。</p>
 */
public class CrossTenantAuditDbSink implements CrossTenantAuditSink {

    private static final Logger log = LoggerFactory.getLogger(CrossTenantAuditDbSink.class);

    private final CrossTenantAuditRowRepository repository;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    /** 生产装配：单线程守护执行器（尽力口径，队列无界防丢事件；停机钩子随 bean 销毁）。 */
    public CrossTenantAuditDbSink(CrossTenantAuditRowRepository repository) {
        this(repository, defaultExecutor(), true);
    }

    /** 测试/定制装配：外部注入执行器（直接执行器=同步落库，便于断言）。 */
    CrossTenantAuditDbSink(CrossTenantAuditRowRepository repository, ExecutorService executor, boolean ownsExecutor) {
        this.repository = repository;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    private static ExecutorService defaultExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "cross-tenant-audit-sink");
                    thread.setDaemon(true);
                    return thread;
                });
        return executor;
    }

    @Override
    public void accept(CrossTenantAuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            executor.execute(() -> persist(event));
        } catch (RejectedExecutionException ex) {
            log.error("跨租户审计落库提交被拒，丢弃该行（异步尽力口径，同步日志兜底）：operator={} accessPoint={}",
                    event.operator(), event.accessPoint(), ex);
        }
    }

    /** 落库单段（尽力口径：失败 ERROR 告警并丢弃该行，主流程零影响，AC-3）。 */
    private void persist(CrossTenantAuditEvent event) {
        try {
            repository.save(toRow(event));
        } catch (RuntimeException ex) {
            log.error("跨租户审计落库失败，丢弃该行（主流程零影响）：operator={} tenant={} bypass={} accessPoint={}",
                    event.operator(), event.tenantId(), event.bypass(), event.accessPoint(), ex);
        }
    }

    /** 事件 → 落库行（字段归一与事件载体一致：operator 缺失 system、其余缺失 -）。 */
    private static CrossTenantAuditRow toRow(CrossTenantAuditEvent event) {
        return new CrossTenantAuditRow(
                normalize(event.operator(), "system"),
                normalize(event.tenantId(), "-"),
                normalize(event.accessPoint(), "-"),
                normalize(event.traceId(), "-"),
                normalize(event.bypass(), CrossTenantAuditEvent.BYPASS_FALSE));
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 停机钩子（仅自有执行器；队列余量尽力落库后退出）。 */
    @PreDestroy
    public void shutdown() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
