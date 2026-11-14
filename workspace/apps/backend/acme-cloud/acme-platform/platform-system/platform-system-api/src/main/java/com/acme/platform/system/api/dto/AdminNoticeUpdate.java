package com.acme.platform.system.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 通知公告编辑请求（REQ-20261114-120102：契约未约定版本头，宽松乐观锁——version 传则校验、缺省放行；
 * 已发布公告编辑被拒=NOTICE_PUBLISHED）。
 */
public record AdminNoticeUpdate(@NotBlank @Size(max = 128) String title,
                                @NotBlank @Size(max = 65535) String content,
                                Long version) {
}
