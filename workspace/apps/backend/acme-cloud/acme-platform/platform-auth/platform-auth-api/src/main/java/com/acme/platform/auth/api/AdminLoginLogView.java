package com.acme.platform.auth.api;

import java.time.Instant;

/**
 * 登录日志视图（REQ-20261114-120105 §API GET /admin/v1/auth/login-logs 行形态）：
 * 仅插入不改的登录审计记录（platform-auth V6 sys_login_log 映射）。
 * wire 形状（frontend 对齐口径）：id 以字符串承载（雪花 19 位防 JS 精度丢失），camelCase。
 *
 * @param id         日志 id（雪花，字符串）
 * @param username   登录用户名（C 端为手机号）
 * @param userType   端别（B/C，与会话 channel 一致）
 * @param ip         登录来源 IP
 * @param userAgent  登录 UA
 * @param result     结果（SUCCESS/FAILURE）
 * @param failReason 失败原因（错误码；成功为 null）
 * @param traceId    登录请求链路 traceId
 * @param createdAt  记录时间
 */
public record AdminLoginLogView(String id, String username, String userType, String ip, String userAgent,
                                String result, String failReason, String traceId, Instant createdAt) {
}
