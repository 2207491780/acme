package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 站内信发送请求（REQ-20261114-120102：发送给指定用户 recipientIds/title/content）。
 */
public record AdminMessageSend(@NotEmpty @Size(max = 500) List<Long> recipientIds,
                               @NotBlank @Size(max = 128) String title,
                               @NotBlank @Size(max = 65535) String content) {
}
