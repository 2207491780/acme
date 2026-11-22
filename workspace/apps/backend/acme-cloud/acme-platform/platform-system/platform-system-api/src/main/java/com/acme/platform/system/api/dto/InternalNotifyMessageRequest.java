package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 内部服务告警消息请求（REQ-20261122-150756 确认④；POST /internal/v1/notify/messages，
 * X-Acme-Token 内部令牌校验由 internal 面过滤器承载，不经网关）。
 *
 * @param sender  服务主体字符串（如 'platform-scheduler'；仅溯源承载，sys_message.sender_id
 *                为数值主体，内部触发扇出恒记系统主体 0）
 * @param title   消息标题（必填；调度告警口径含 jobKey+reason）
 * @param content 消息内容（可空；调度告警口径含 executionId/attempt/error）
 */
public record InternalNotifyMessageRequest(@NotBlank @Size(max = 64) String sender,
                                           @NotBlank @Size(max = 128) String title,
                                           @Size(max = 65535) String content) {
}
