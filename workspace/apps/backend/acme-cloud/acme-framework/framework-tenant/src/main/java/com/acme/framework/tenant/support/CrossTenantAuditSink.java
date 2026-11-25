package com.acme.framework.tenant.support;

/**
 * 跨租户审计投递 SPI（FR-004；REQ-20261125-001736 AC-2/AC-3）：{@link CrossTenantAuditLogger}
 * 在结构化日志之后把事件投递给容器内装配的 sink（旁路 ROOT 与直读事件都投递）。
 *
 * <p>实现契约：异步与写库职责在 sink 实现侧——accept 必须非阻塞（仅入队）、内部消化失败
 * （尽力落库口径，写失败 ERROR 告警并丢弃该行，不向调用方抛出）；未装配任何 sink 时框架仅
 * 输出同步日志、不报错（框架可独立运行）。同步结构化日志始终是审计兜底事实源，sink 落库
 * 失败不阻断主流程（可用性优先于审计强一致）。</p>
 */
public interface CrossTenantAuditSink {

    /** 投递一次跨租户审计事件（实现侧异步；实现不得抛出，不得阻塞调用线程）。 */
    void accept(CrossTenantAuditEvent event);
}
