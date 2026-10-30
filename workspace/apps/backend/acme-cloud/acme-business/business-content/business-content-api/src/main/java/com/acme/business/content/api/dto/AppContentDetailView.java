package com.acme.business.content.api.dto;

import java.util.List;

/**
 * C 端内容详情（openapi AppContentDetailView；FR-005 正文必做 Q-10=A、cover 预留可选）。
 * related 为相关推荐（同分类已发布最新 ≤6，架构设计取舍 #10）；id 雪花 Long（wire 字符串化）。
 */
public record AppContentDetailView(
        Long id,
        String title,
        String categoryCode,
        String categoryName,
        String excerpt,
        String content,
        String cover,
        List<AppContentItemView> related) {

    public AppContentDetailView {
        related = related == null ? List.of() : List.copyOf(related);
    }
}
