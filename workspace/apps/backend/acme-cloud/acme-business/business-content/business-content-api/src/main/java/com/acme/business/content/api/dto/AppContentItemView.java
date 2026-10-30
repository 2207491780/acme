package com.acme.business.content.api.dto;

/**
 * C 端内容列表项（openapi AppContentItemView；App* 视图 11.5.2 裁剪内部字段）。
 * 不可变 DTO、字段包装语义（9.2.1）；id 为雪花 Long（12.3.1，wire 由 common-serialization 序列化为字符串）。
 */
public record AppContentItemView(
        Long id,
        String title,
        String categoryCode,
        String categoryName,
        String excerpt) {
}
