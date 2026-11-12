package com.acme.framework.tx.support;

/**
 * 失败回滚策略（FR-022）。
 */
public enum RollbackPolicy {

    /** 任一失败即回滚（默认）。 */
    ROLLBACK_ON_FAILURE,

    /** 仅未检查异常回滚（受检异常上抛不回滚，由调用方处置）。 */
    ROLLBACK_ON_UNCHECKED
}
