package com.acme.platform.auth.api;

/**
 * 认证域错误码文本常量（Backend.md 10.5.2，serviceId=platform-auth；架构设计 8.1 表）：
 * 完整枚举（含 HTTP 映射）归 platform-auth-service AuthError，两处取值一致由单测锁定。
 */
public final class AuthErrorCodes {

    /** 用户名或密码错误（401，统一失败提示不区分账号存在性，FR-005）。 */
    public static final String LOGIN_FAILED = "platform-auth-01-0001";

    /** 登录尝试过多账号临时锁定（423）。 */
    public static final String ACCOUNT_LOCKED = "platform-auth-01-0002";

    /** 账号已停用（403）。 */
    public static final String ACCOUNT_DISABLED = "platform-auth-01-0003";

    /** 登出失败（500；不存在「响应成功但令牌仍可用」状态）。 */
    public static final String LOGOUT_FAILED = "platform-auth-01-0004";

    /** 该用户尚未设置密码（409，预留）。 */
    public static final String CREDENTIAL_NOT_INITIALIZED = "platform-auth-02-0001";

    /** 密码不符合要求（400）。 */
    public static final String PASSWORD_POLICY_VIOLATED = "platform-auth-02-0002";

    // ---- 03 段：C 端消费者认证域（consumer-auth-refresh-dataperm 特性）----

    /** 验证码发送过于频繁（429：60s/手机号频控）。 */
    public static final String CAPTCHA_SEND_LIMITED = "platform-auth-03-0001";

    /** 验证码错误（401；5 次失败作废重发）。 */
    public static final String CAPTCHA_INVALID = "platform-auth-03-0002";

    /** 验证码已过期（401；5 分钟有效期）。 */
    public static final String CAPTCHA_EXPIRED = "platform-auth-03-0003";

    /** C 端账户已停用（403）。 */
    public static final String CONSUMER_DISABLED = "platform-auth-03-0004";

    /** 刷新令牌无效或已使用（401：一次性轮换，重复使用即拒绝）。 */
    public static final String REFRESH_TOKEN_INVALID = "platform-auth-03-0005";

    private AuthErrorCodes() {
    }
}
