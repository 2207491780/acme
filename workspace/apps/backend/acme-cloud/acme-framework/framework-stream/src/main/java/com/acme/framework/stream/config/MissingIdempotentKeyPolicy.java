package com.acme.framework.stream.config;

/**
 * 幂等键缺失处置（FR-014 异常分支、架构设计 8.4：默认 REJECT）。
 */
public enum MissingIdempotentKeyPolicy {

    /** 默认：拒绝消费（进重试/死信）。 */
    REJECT,

    /** 放行并告警（不做去重，受控场景）。 */
    ACCEPT_WITH_WARN
}
