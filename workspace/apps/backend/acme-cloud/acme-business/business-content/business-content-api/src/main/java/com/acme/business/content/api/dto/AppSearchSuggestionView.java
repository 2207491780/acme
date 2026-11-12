package com.acme.business.content.api.dto;

/**
 * C 端搜索联想候选（openapi AppSearchSuggestionView；Q-05=A 轻量形态，候选 ≤10）。
 * id 雪花 Long（wire 字符串化）。
 */
public record AppSearchSuggestionView(Long id, String title) {
}
