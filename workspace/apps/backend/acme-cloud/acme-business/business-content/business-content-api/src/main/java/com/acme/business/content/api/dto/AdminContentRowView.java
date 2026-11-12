package com.acme.business.content.api.dto;

import com.acme.business.content.api.ContentStatus;

import java.time.Instant;

/**
 * B 端内容管理行（openapi AdminContentRowView；Admin* 视图 11.5.1，不含正文 content）。
 * Long 语义字段（id/views/version）Java 侧保持 Long，wire 由 common-serialization 序列化为字符串
 * （G3 裁定：平台 Long→String 全局约定为准；同 AdminUserView 先例）。
 *
 * @param version 乐观锁版本（QD-04：行内改题回传 expectedVersion 用）
 */
public record AdminContentRowView(
        Long id,
        String title,
        String categoryCode,
        String categoryName,
        String author,
        ContentStatus status,
        Long views,
        Instant updatedAt,
        Long version) {
}
