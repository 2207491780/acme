package com.acme.platform.auth.domain;

/**
 * C 端账户状态（C 端无 RBAC，资源归属校验归业务侧，11.4）。
 */
public enum ConsumerStatus {

    /** 正常。 */
    ENABLED,

    /** 停用（登录被拒）。 */
    DISABLED
}
