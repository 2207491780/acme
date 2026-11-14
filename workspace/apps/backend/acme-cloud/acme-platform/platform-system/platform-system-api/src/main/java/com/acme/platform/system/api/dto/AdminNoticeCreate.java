package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 通知公告创建请求（REQ-20261114-120102：新建草稿，content 为富文本 HTML）。
 */
public record AdminNoticeCreate(@NotBlank @Size(max = 128) String title,
                                @NotBlank @Size(max = 65535) String content) {
}
