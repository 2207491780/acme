package com.acme.framework.security.revocation;

/**
 * 撤销检查异常处置决策组件（FR-004 即时失效；REQ-20261125-001736 AC-7）：读配置
 * {@code acme.security.revocation.fail-closed}（默认 false 与现状完全一致），网关
 * {@code RevocationCheckFilter} 与单体边缘 {@code BootstrapAuthFilter} 的 Redis 检查
 * 异常分支统一经本组件决策——true=拒绝（HTTP 503 + REVOCATION_CHECK_UNAVAILABLE），
 * false=FAIL_OPEN 放行 + WARN。开关装配进 SecurityAutoConfiguration/GatewayAutoConfiguration。
 */
public class RevocationCheckPolicy {

    /** fail-closed 开关（SecurityProperties.Revocation.failClosed，默认 false）。 */
    private final boolean failClosed;

    public RevocationCheckPolicy(boolean failClosed) {
        this.failClosed = failClosed;
    }

    /** 检查异常是否拒绝请求（true=安全优先 503 拒绝；false=可用性优先放行 + WARN）。 */
    public boolean rejectOnCheckFailure() {
        return failClosed;
    }
}
