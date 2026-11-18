package com.acme.platform.infra.api.dto;

import java.util.Map;

/**
 * 直传准备视图（REQ-20261117-045754 §API DirectUploadPreparationView）：
 * objectKey（带租户段新格式）/ uploadUrl / method / headers（客户端 PUT 须原样携带，含 Content-Type）/
 * expiresInSeconds / ticket（仅 LOCAL 票据模式返回，MINIO/OSS 预签名为 null）。
 */
public record DirectUploadPreparationView(
        String objectKey,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        long expiresInSeconds,
        String ticket) {
}
