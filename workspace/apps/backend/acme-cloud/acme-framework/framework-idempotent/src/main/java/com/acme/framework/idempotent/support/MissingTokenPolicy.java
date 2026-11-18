package com.acme.framework.idempotent.support;

/**
 * 幂等 token 缺失处置（FR-001：可配置且默认可判定；取舍 #8）。
 */
public enum MissingTokenPolicy {

    /** 拒绝（默认，@Idempotent 语义不被静默绕过）。 */
    REJECT,

    /** 放行（跳过幂等机制执行业务）。 */
    PASS
}
