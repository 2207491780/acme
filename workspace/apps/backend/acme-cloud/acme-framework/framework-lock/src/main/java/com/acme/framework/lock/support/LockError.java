package com.acme.framework.lock.support;

import com.acme.common.core.ErrorCode;

/**
 * framework-lock 错误码枚举（Q-D06 同型续编：SYS-07 段，DP-008 定稿后回填）。
 */
public enum LockError implements ErrorCode {

    /** 锁获取失败（FR-009/012）。 */
    LOCK_ACQUIRE_FAILED("SYS-07-0001", "分布式锁获取失败"),

    /** 执行中失锁（FR-011 异常分支）。 */
    LOCK_LOST("SYS-07-0002", "分布式锁已丢失"),

    /** 锁服务不可用（FR-009 异常分支：Redis 不可用显式可感知）。 */
    LOCK_UNAVAILABLE("SYS-07-0003", "分布式锁服务不可用");

    private final String code;
    private final String defaultMessage;

    LockError(String code, String defaultMessage) {
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
