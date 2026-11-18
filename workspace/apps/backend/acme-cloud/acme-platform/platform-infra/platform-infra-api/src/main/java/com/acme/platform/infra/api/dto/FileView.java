package com.acme.platform.infra.api.dto;

import java.time.Instant;

/**
 * 文件 B 端视图（REQ-20261114-071607 §API FileView；字段集 = id/originalName/size/contentType/
 * url/bizType/bizId/createdAt）。id 取字符串形态（雪花主键防 JS 精度丢失，同 AdminAuditEventView 口径）；
 * url 为对象访问地址（本地盘 = acme.storage.local.url-base 拼接 ObjectKey）。
 */
public record FileView(
        String id,
        String originalName,
        Long size,
        String contentType,
        String url,
        String bizType,
        String bizId,
        Instant createdAt) {
}
