package com.acme.framework.job.support;

import com.acme.common.core.ErrorCode;

/**
 * framework-job 错误码枚举（Q-D06 同型续编：SYS-10 段，DP-008 定稿后回填）。
 */
public enum JobError implements ErrorCode {

    /** 任务不存在（FR-028 异常分支）。 */
    TASK_NOT_FOUND("SYS-10-0001", "任务不存在"),

    /** 任务执行中（FR-028 异常分支：不排队）。 */
    TASK_RUNNING("SYS-10-0002", "任务正在执行"),

    /** 锁提供方不可用（FR-025 异常分支：互斥语义优先，不得双跑）。 */
    LOCK_PROVIDER_UNAVAILABLE("SYS-10-0003", "任务互斥服务不可用");

    private final String code;
    private final String defaultMessage;

    JobError(String code, String defaultMessage) {
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
