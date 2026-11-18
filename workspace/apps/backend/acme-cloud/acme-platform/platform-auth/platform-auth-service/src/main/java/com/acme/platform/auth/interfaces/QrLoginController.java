package com.acme.platform.auth.interfaces;

import com.acme.common.core.Result;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.api.QrTicketCreatedView;
import com.acme.platform.auth.api.QrTicketInfoView;
import com.acme.platform.auth.api.QrTicketStatusView;
import com.acme.platform.auth.application.QrLoginUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扫码登录接口（REQ-20261119-040838 §API；路径含端别前缀 /admin/v1/auth/qr-tickets**）：
 * 创建与轮询为公开豁免端点（登录页未认证态使用——网关 + 服务侧 acme.security.public-paths
 * 双侧一致最小集，FR-012：精确路径 {@code /admin/v1/auth/qr-tickets} 创建＋单段通配
 * {@code /admin/v1/auth/qr-tickets/*} 轮询，AntPathMatcher 语义下 info/confirm/cancel 双段后缀
 * 不命中通配、保持认证）；info/confirm/cancel 为认证端点（B 端内部令牌链路——网关验签转发后
 * InternalTokenAuthFilter 绑定 UserContext/TenantContext，确认者即 B 端会话主体，用例内直读上下文）；
 * confirm/cancel 写动作纳入审计（REQ-20261117-061017 确认③先例）。
 */
@RestController
@RequestMapping("/admin/v1/auth")
public class QrLoginController {

    private final QrLoginUseCase qrLoginUseCase;

    public QrLoginController(QrLoginUseCase qrLoginUseCase) {
        this.qrLoginUseCase = qrLoginUseCase;
    }

    /**
     * 创建扫码票据（公开端点，无 body）：IP 频控 10 次/分钟/IP（超限 QR_TICKET_LIMITED 429）；
     * 返回 ticketId / 二维码 payload（{@code {"t":"<ticketId>"}}）/ 有效期 120s。
     */
    @PostMapping("/qr-tickets")
    public Result<QrTicketCreatedView> create(HttpServletRequest servletRequest) {
        return Result.ok(qrLoginUseCase.create(ClientRequests.resolveIp(servletRequest)));
    }

    /**
     * 轮询票据状态（公开端点，只读不消费）：未知/过期 id 统一 expired＋token null（不泄露存在性差异）；
     * confirmed 时 token 携确认者 B 端令牌（web setSession 进入主界面）。
     */
    @GetMapping("/qr-tickets/{ticketId}")
    public Result<QrTicketStatusView> poll(@PathVariable String ticketId) {
        return Result.ok(qrLoginUseCase.poll(ticketId));
    }

    /**
     * 扫码取信息（认证端点，B 端）：created→scanned CAS 幂等推进（重复 info 不回退 confirmed）；
     * cancelled/缺失映射业务失败码（QR_TICKET_CANCELLED / QR_TICKET_EXPIRED，HTTP 200 业务失败信封）。
     */
    @GetMapping("/qr-tickets/{ticketId}/info")
    public Result<QrTicketInfoView> info(@PathVariable String ticketId) {
        return Result.ok(qrLoginUseCase.info(ticketId));
    }

    /**
     * 确认登录（认证端点，B 端；确认者 = 内部令牌链路绑定的 B 端会话主体）：原子单次，
     * 仅 created/scanned 可确认（created 直接确认放行——扫码即确认快速路径）；
     * 成功互踢旧 B 会话并签发确认者令牌（confirmed＋token 回写供轮询重复取，TTL 内）；
     * 二次确认 QR_TICKET_INVALID（票据保留）。
     */
    @PostMapping("/qr-tickets/{ticketId}/confirm")
    @AuditLog(action = "auth.qr.confirm", targetType = "qrTicket", contentParams = {"ticketId"})
    public Result<AdminTokenIssued> confirm(@PathVariable String ticketId, HttpServletRequest servletRequest) {
        return Result.ok(qrLoginUseCase.confirm(ticketId, ClientRequests.resolveIp(servletRequest),
                ClientRequests.resolveUserAgent(servletRequest)));
    }

    /**
     * 取消票据（认证端点，B 端；幂等）：CAS 置 cancelled，缺失/已终态一律成功（Result&lt;Void&gt;）。
     */
    @PostMapping("/qr-tickets/{ticketId}/cancel")
    @AuditLog(action = "auth.qr.cancel", targetType = "qrTicket", contentParams = {"ticketId"})
    public Result<Void> cancel(@PathVariable String ticketId) {
        qrLoginUseCase.cancel(ticketId);
        return Result.ok(null);
    }
}
