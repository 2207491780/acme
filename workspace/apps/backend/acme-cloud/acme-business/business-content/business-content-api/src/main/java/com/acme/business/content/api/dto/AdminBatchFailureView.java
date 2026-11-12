package com.acme.business.content.api.dto;

/**
 * 批量逐条失败明细（openapi AdminBatchFailureView）。
 *
 * @param code    失败错误码（如 business-content-01-0001）
 * @param message 面向调用方提示（可空）
 */
public record AdminBatchFailureView(String id, String code, String message) {
}
