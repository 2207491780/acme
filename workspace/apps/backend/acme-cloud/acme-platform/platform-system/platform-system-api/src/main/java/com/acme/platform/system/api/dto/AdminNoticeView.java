package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.NoticeStatus;

import java.time.Instant;

/**
 * 通知公告视图（REQ-20261114-120102）。
 */
public record AdminNoticeView(Long id,
                              String title,
                              String content,
                              NoticeStatus status,
                              Instant publishAt,
                              Instant createdAt,
                              Instant updatedAt,
                              Long version) {
}
