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

    // ---- 04 段：登录安全域——在线用户管理（REQ-20261114-120105；业务失败=Result.code≠0，HTTP 200）----

    /** 目标会话不存在（或已过期 / 已终态，按不存在语义统一处理）。 */
    public static final String SESSION_NOT_FOUND = "platform-auth-04-0001";

    /** 非超管踢超管会话被拒绝（超管互踢允许）。 */
    public static final String REVOKE_SUPERADMIN_FORBIDDEN = "platform-auth-04-0002";

    // ---- 05 段：超管跨租户切换（REQ-20261117-025200 确认记录④）----

    /** 非内置超管不允许跨租户切换（403 权限语义；前置经 system 内部鉴权面实查内置角色）。 */
    public static final String TENANT_SWITCH_FORBIDDEN = "platform-auth-05-0001";

    /** 切换目标租户不可用（未知 / 停用 / 已过期统一口径，不泄露目标租户存在性差异）。 */
    public static final String TENANT_SWITCH_FAILED = "platform-auth-05-0002";

    // ---- 06 段：APP 扫码登录票据域（REQ-20261119-040838 确认记录③④）----

    /** 扫码票据创建过于频繁（429：10 次/分钟/IP 频控）。 */
    public static final String QR_TICKET_LIMITED = "platform-auth-06-0001";

    /** 扫码票据不存在或已过期（120s TTL 自然失效，Redis 键缺失兜底）。 */
    public static final String QR_TICKET_EXPIRED = "platform-auth-06-0002";

    /** 扫码票据已取消（APP 侧取消后 info/confirm 拒绝）。 */
    public static final String QR_TICKET_CANCELLED = "platform-auth-06-0003";

    /** 扫码票据状态不允许当前操作（如已确认后再次确认）。 */
    public static final String QR_TICKET_INVALID = "platform-auth-06-0004";

    private AuthErrorCodes() {
    }
}
