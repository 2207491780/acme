package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 通知偏好视图（REQ-20261119-082531 确认记录⑤，GET/PUT /admin/v1/system/me/preferences 双面载体）：
 * messagePush=站内信新消息 APP 推送开关（持久化为 sys_user_preference.prefs jsonb 的
 * {@code {"messagePush": boolean}}；未设偏好行时 GET 回默认 false）。
 * PUT 侧以 @Valid 承载非空校验；单一 record 对称承载读写载荷（同 AdminStatusUpdate 先例）。
 */
public record AdminPreferenceView(@NotNull Boolean messagePush) {
}
