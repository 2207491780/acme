package com.acme.platform.system.api.dto;

import java.time.Instant;

/**
 * 站内信视图（REQ-20261114-120102：收件箱语义，收件人可见；sender 仅作展示追溯）。
 */
public record AdminMessageView(Long id,
                               Long senderId,
                               Long recipientId,
                               String title,
                               String content,
                               Boolean isRead,
                               Instant readAt,
                               Instant createdAt) {
}
