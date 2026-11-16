package com.acme.platform.auth;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.auth.api.AuthErrorCodes;

/**
 * 认证域错误码枚举（10.5.2 三段式，serviceId=platform-auth；架构设计 8.1 表）。
 * 码值与 platform-auth-api AuthErrorCodes 常量一一对应。
 */
public enum AuthError implements ErrorCode, HttpStatusAware {

    /** 用户名或密码错误（统一失败提示，FR-005）。 */
    LOGIN_FAILED(AuthErrorCodes.LOGIN_FAILED, "用户名或密码错误", 401),

    /** 登录尝试过多账号临时锁定（基础防爆破，Q-002）。 */
    ACCOUNT_LOCKED(AuthErrorCodes.ACCOUNT_LOCKED, "登录尝试过多，账号已临时锁定", 423),

    /** 账号已停用（管理语义明确提示，与 FR-005 不冲突——仅约束存在性区分）。 */
    ACCOUNT_DISABLED(AuthErrorCodes.ACCOUNT_DISABLED, "账号已停用", 403),

    /** 登出失败（撤销集写入失败；不得出现「响应成功但令牌仍可用」状态）。 */
    LOGOUT_FAILED(AuthErrorCodes.LOGOUT_FAILED, "登出失败，请重试", 500),

    /** 该用户尚未设置密码（预留编码，本期登录侧统一 LOGIN_FAILED 不启用）。 */
    CREDENTIAL_NOT_INITIALIZED(AuthErrorCodes.CREDENTIAL_NOT_INITIALIZED, "该用户尚未设置密码", 409),

    /** 密码不符合要求（长度 8~64）。 */
    PASSWORD_POLICY_VIOLATED(AuthErrorCodes.PASSWORD_POLICY_VIOLATED, "密码不符合要求", 400),

    // ---- 03 段：C 端消费者认证域（consumer-auth-refresh-dataperm）----

    /** 验证码发送过于频繁（60s/手机号频控）。 */
    CAPTCHA_SEND_LIMITED(AuthErrorCodes.CAPTCHA_SEND_LIMITED, "验证码发送过于频繁，请稍后再试", 429),

    /** 验证码错误（5 次失败作废重发）。 */
    CAPTCHA_INVALID(AuthErrorCodes.CAPTCHA_INVALID, "验证码错误", 401),

    /** 验证码已过期（5 分钟有效期）。 */
    CAPTCHA_EXPIRED(AuthErrorCodes.CAPTCHA_EXPIRED, "验证码已过期，请重新获取", 401),

    /** C 端账户已停用。 */
    CONSUMER_DISABLED(AuthErrorCodes.CONSUMER_DISABLED, "账号已停用", 403),

    /** 刷新令牌无效或已使用（一次性轮换，重复使用即拒绝）。 */
    REFRESH_TOKEN_INVALID(AuthErrorCodes.REFRESH_TOKEN_INVALID, "刷新令牌无效或已使用", 401),

    // ---- 04 段：登录安全域——在线用户管理（REQ-20261114-120105）----
    // 契约 §API：业务失败=Result.code≠0（HTTP 200），故两码 httpStatus 均登记 200（信封承载失败语义）。

    /** 目标会话不存在（缺失 / 已过期 / 已终态统一按不存在处理）。 */
    SESSION_NOT_FOUND(AuthErrorCodes.SESSION_NOT_FOUND, "会话不存在", 200),

    /** 非超管踢超管会话被拒绝（超管互踢允许）。 */
    REVOKE_SUPERADMIN_FORBIDDEN(AuthErrorCodes.REVOKE_SUPERADMIN_FORBIDDEN, "无权对超级管理员会话执行踢下线", 200),

    // ---- 05 段：超管跨租户切换（REQ-20261117-025200 确认记录④）----

    /** 非内置超管不允许跨租户切换（前置经 system 内部鉴权面实查内置角色，roles 不作信任锚）。 */
    TENANT_SWITCH_FORBIDDEN(AuthErrorCodes.TENANT_SWITCH_FORBIDDEN, "无权执行跨租户切换", 403),

    /** 切换目标租户不可用（未知 / 停用 / 已过期统一口径，不泄露目标租户存在性差异）。 */
    TENANT_SWITCH_FAILED(AuthErrorCodes.TENANT_SWITCH_FAILED, "目标租户不可用，无法切换", 409);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    AuthError(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
