package com.acme.platform.system.api.dto;

/**
 * 个人偏好视图（REQ-20261121-200415 确认记录②③④，GET/PUT /admin/v1/system/me/preferences 双面载体）：
 * 两域可空承载——
 * <ul>
 *   <li>messagePush：站内信新消息 APP 推送开关（null=不动该域；GET 未设回 false，REQ-082531 口径）；</li>
 *   <li>themePreference：主题偏好 jsonb blob（null=不动该域；GET 未设回 null，前端以 core
 *       DEFAULT_PREFERENCES 兜底，后端不内置主题默认值——防双源漂移）。</li>
 * </ul>
 * PUT 语义为按域 upsert（至少一域非 null，否则 400）；themePreference 结构校验（顶层键白名单/
 * preferences 逐键白名单/枚举/hex/extensions 限额）由用例层校验器 fail-closed 承载，非法回
 * 400 既有偏好校验错误码（REQ-082531 终稿口径，不新增错误码段），原数据不动。
 * 向后兼容 REQ-082531 旧形状 {@code {messagePush}} PUT（themePreference 域不被触碰）。
 */
public record AdminPreferenceView(Boolean messagePush, Object themePreference) {
}
