package com.acme.framework.security.support;

/**
 * 数据权限判定结果（FR-017，7.5）。
 */
public enum DataPermissionDecision {

    /** 放行。 */
    ALLOW,

    /** 拒绝（标准失败响应 DATA_PERMISSION_DENIED，7.5）。 */
    DENY
}
