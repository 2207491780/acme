package com.acme.framework.security.support;

/**
 * 数据范围五档模型（REQ-20261114-120104 确认记录①；框架只承载档位契约，不含业务规则 8.1.2）：
 * ALL 全部 / DEPT 本部门 / DEPT_AND_CHILD 本部门及子部门 / SELF 仅本人 / CUSTOM 自定义部门集
 * （自定义集归业务侧 sys_role_dept 存储，框架不解释其来源）。
 */
public enum DataScope {

    /** 全部数据（无限制）。 */
    ALL,

    /** 本部门（不含子部门）。 */
    DEPT,

    /** 本部门及子部门。 */
    DEPT_AND_CHILD,

    /** 仅本人。 */
    SELF,

    /** 自定义部门集（业务侧维护部门清单）。 */
    CUSTOM;

    /** 存量角色缺省档（V9 迁移列缺省 ALL，语义不变）。 */
    public static final DataScope DEFAULT = ALL;

    /**
     * 宽松解析（列值 → 档位）：null / 空白 / 未知取值返回 null，由调用方决定缺省口径
     * （不做静默兜底，避免掩盖脏数据）。
     */
    public static DataScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 档位宽序（合并多角色取最宽档的静态可比口径）：ALL &gt; DEPT_AND_CHILD &gt; DEPT &gt; CUSTOM &gt; SELF。
     * CUSTOM 的实际宽窄取决于自定义集合，无法静态比较，按保守序置于 DEPT 之下（仅用于档位标签择宽，
     * 可见集合并语义不受此序影响）。
     */
    public int rank() {
        return switch (this) {
            case ALL -> 5;
            case DEPT_AND_CHILD -> 4;
            case DEPT -> 3;
            case CUSTOM -> 2;
            case SELF -> 1;
        };
    }

    /** 取更宽档位（rank 相同任意取一，语义等价）。 */
    public static DataScope widest(DataScope a, DataScope b) {
        return a.rank() >= b.rank() ? a : b;
    }
}
