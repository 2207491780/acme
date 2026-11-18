package com.acme.platform.auth.api;

/**
 * 扫码票据轮询状态（REQ-20261119-040838 确认记录④⑥，GET /admin/v1/auth/qr-tickets/{ticketId}
 * 公开端点，只读不消费）：status ∈ created/scanned/confirmed/cancelled/expired；
 * confirmed 时 token 携带确认者 B 端令牌（AdminTokenIssued，web setSession 进入主界面），
 * 其余状态 token 为 null；未知 / 过期 id 统一返回 status=expired（TTL 兜底，不泄露存在性差异）。
 *
 * @param status 票据状态（created/scanned/confirmed/cancelled/expired）
 * @param token  确认者 B 端令牌（仅 confirmed 非空；wire userId 为 string）
 */
public record QrTicketStatusView(String status, AdminTokenIssued token) {
}
