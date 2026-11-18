package com.acme.platform.auth.api;

/**
 * 扫码票据创建结果（REQ-20261119-040838 确认记录④⑤，POST /admin/v1/auth/qr-tickets 公开端点）：
 * ticketId 供轮询与 APP 扫码确认；qrPayload 为二维码内容紧凑 JSON（{@code {"t":"<ticketId>"}}，
 * web 端以 qrcode 库渲染 dataURL、APP 相机解析出 t 后调 info/confirm）；expiresInSeconds 与
 * 票据 TTL 一致（120s，过期后轮询返回 expired）。
 *
 * @param ticketId        票据 ID（32 位 UUID，无连字符）
 * @param qrPayload       二维码内容（紧凑 JSON {@code {"t":"<ticketId>"}}）
 * @param expiresInSeconds 票据有效期（秒，固定 120）
 */
public record QrTicketCreatedView(String ticketId, String qrPayload, int expiresInSeconds) {
}
