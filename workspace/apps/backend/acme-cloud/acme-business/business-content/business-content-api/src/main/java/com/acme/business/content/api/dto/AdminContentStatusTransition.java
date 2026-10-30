package com.acme.business.content.api.dto;

import com.acme.business.content.api.ContentStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 状态流转请求（openapi AdminContentStatusTransition；FR-009，合法集合 BR-013）。
 */
public record AdminContentStatusTransition(
        @NotNull ContentStatus targetStatus) {
}
