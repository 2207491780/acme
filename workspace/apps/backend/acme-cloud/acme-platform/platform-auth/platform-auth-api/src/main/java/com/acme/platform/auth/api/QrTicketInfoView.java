package com.acme.platform.auth.api;

/**
 * 扫码取信息结果（REQ-20261119-040838 确认记录③，GET /admin/v1/auth/qr-tickets/{ticketId}/info
 * B 端认证端点）：APP 扫码后取票面并推进状态 created→scanned（幂等——重复 info 不回退 confirmed）；
 * expired/cancelled → 业务失败码（QR_TICKET_EXPIRED / QR_TICKET_CANCELLED 族）。
 *
 * @param status    票据当前状态（扫码后为 scanned；已确认票幂等返回 confirmed）
 * @param createdAt 票据创建时间（ISO-8601；APP 展示用）
 */
public record QrTicketInfoView(String status, String createdAt) {
}
