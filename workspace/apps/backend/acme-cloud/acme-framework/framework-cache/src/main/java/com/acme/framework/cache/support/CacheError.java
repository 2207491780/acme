package com.acme.framework.cache.support;

/**
 * framework-cache 错误码枚举（Q-D06 同型续编：SYS-06 段，DP-008 定稿后回填）。
 */
public enum CacheError implements com.acme.common.core.ErrorCode {

    /** 缓存基础设施不可用（FR-005 异常分支 FAIL 策略、序列化失败）。 */
    CACHE_UNAVAILABLE("SYS-06-0001", "缓存暂不可用"),

    /** 回源加载失败（FR-005）。 */
    LOAD_FAILED("SYS-06-0002", "缓存回源失败"),

    /** 缺少租户上下文（REQ-20261125-001736 AC-4：租户缓存键 fail-closed，未绑定且旁路未激活）。 */
    TENANT_REQUIRED("SYS-06-0003", "缺少租户上下文");

    private final String code;
    private final String defaultMessage;

    CacheError(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
