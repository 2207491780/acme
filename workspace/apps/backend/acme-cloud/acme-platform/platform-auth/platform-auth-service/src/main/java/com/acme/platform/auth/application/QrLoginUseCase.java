package com.acme.platform.auth.application;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.security.support.SecurityError;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.api.QrTicketCreatedView;
import com.acme.platform.auth.api.QrTicketInfoView;
import com.acme.platform.auth.api.QrTicketStatusView;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.redis.QrTicketStore;
import com.acme.platform.auth.infrastructure.redis.QrTicketStore.QrTicket;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.system.api.AuthProfileView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 扫码登录用例（REQ-20261119-040838 确认记录③④⑤⑥）：
 * <ul>
 *   <li>create（公开）：IP 频控（10 次/分钟/IP，超限 QR_TICKET_LIMITED 429）→ 票据 created（TTL 120s）；
 *       qrPayload = 紧凑 JSON {@code {"t":"<ticketId>"}}（web 渲染二维码、APP 相机解析）。</li>
 *   <li>poll（公开只读）：不消费票据；缺失/过期（Redis TTL 兜底）统一 expired＋token null；
 *       confirmed 携确认者 B 端令牌（TTL 内可重复取，不因轮询失效）。</li>
 *   <li>info（B 令牌）：created→scanned CAS 幂等推进（重复 info 不回退 confirmed）；
 *       cancelled → QR_TICKET_CANCELLED，缺失/过期 → QR_TICKET_EXPIRED（业务失败信封 HTTP 200）。</li>
 *   <li>confirm（B 令牌）：取票（Lua GET+DEL 原子单次）→ 状态权威校验（仅 created/scanned 可确认；
 *       confirmed → QR_TICKET_INVALID 且票据保留供轮询）→ kickPreviousSessions（B 端互踢 Q-003）→
 *       issue(CHANNEL_B, ACCESS) → 会话底账/索引/信息/登录日志（照抄 LoginUseCase 第 7-9 步口径）→
 *       confirmed＋token 回写（剩余 TTL）→ 返回 AdminTokenIssued（wire userId string）。
 *       确认主体取内部令牌链路绑定的 UserContext/TenantContext（即 B 端确认者本人）。</li>
 *   <li>cancel（B 令牌）：CAS 置 cancelled（幂等；缺失/已终态一律成功）。</li>
 * </ul>
 * 编排无整体事务：DB 写经 SessionRecorder 独立事务（10.2.1），Redis 撤销/索引 best-effort（Q-003 同口径）。
 */
@Service
public class QrLoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(QrLoginUseCase.class);

    private final QrTicketStore qrTicketStore;
    private final UserTokenIssuer tokenIssuer;
    private final SessionRecorder sessionRecorder;
    private final SessionRedis sessionRedis;
    private final AuthProfileGateway authProfileGateway;
    private final LoginLogRecorder loginLogRecorder;
    private final JsonMapper jsonMapper;

    public QrLoginUseCase(QrTicketStore qrTicketStore,
                          UserTokenIssuer tokenIssuer,
                          SessionRecorder sessionRecorder,
                          SessionRedis sessionRedis,
                          AuthProfileGateway authProfileGateway,
                          LoginLogRecorder loginLogRecorder,
                          JsonMapper jsonMapper) {
        this.qrTicketStore = qrTicketStore;
        this.tokenIssuer = tokenIssuer;
        this.sessionRecorder = sessionRecorder;
        this.sessionRedis = sessionRedis;
        this.authProfileGateway = authProfileGateway;
        this.loginLogRecorder = loginLogRecorder;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 创建扫码票据（公开端点；IP 频控先行，超限 QR_TICKET_LIMITED）。
     *
     * @param clientIp 客户端 IP（频控键与票据 createdIp）
     * @return ticketId / 二维码 payload（{@code {"t":"<ticketId>"}}）/ 有效期 120s
     */
    public QrTicketCreatedView create(String clientIp) {
        if (!qrTicketStore.tryAcquireCreationQuota(clientIp)) {
            throw new BizException(AuthError.QR_TICKET_LIMITED);
        }
        String ticketId = qrTicketStore.create(clientIp);
        String qrPayload = jsonMapper.writeValueAsString(Map.of("t", ticketId));
        return new QrTicketCreatedView(ticketId, qrPayload,
                (int) QrTicketStore.DEFAULT_TTL.toSeconds());
    }

    /**
     * 轮询票据状态（公开端点，只读不消费；web 每 1.5s 一次）：缺失/过期（TTL 兜底）统一
     * status=expired＋token null（不泄露存在性差异）；confirmed 时 token 非空（AdminTokenIssued，
     * web setSession 进入主界面）。
     */
    public QrTicketStatusView poll(String ticketId) {
        return qrTicketStore.read(ticketId)
                .map(ticket -> new QrTicketStatusView(ticket.status(), ticket.token()))
                .orElseGet(() -> new QrTicketStatusView(QrTicketStore.STATUS_EXPIRED, null));
    }

    /**
     * 扫码取信息（B 端认证）：CAS 幂等推进 created→scanned；重复 info 原样返回（不回退 confirmed）；
     * cancelled/缺失映射业务失败码（HTTP 200 业务失败信封）。
     */
    public QrTicketInfoView info(String ticketId) {
        Optional<QrTicket> ticket = qrTicketStore.markScanned(ticketId);
        if (ticket.isEmpty()) {
            throw new BizException(AuthError.QR_TICKET_EXPIRED);
        }
        QrTicket snapshot = ticket.get();
        if (QrTicketStore.STATUS_CANCELLED.equals(snapshot.status())) {
            throw new BizException(AuthError.QR_TICKET_CANCELLED);
        }
        return new QrTicketInfoView(snapshot.status(), snapshot.createdAt());
    }

    /**
     * 确认登录（B 端认证，原子单次）：取票→状态权威校验→互踢→签发→底账/索引/信息→
     * confirmed＋token 回写（剩余 TTL，轮询可重复取）→登录日志→返回令牌。
     * 取票后校验失败（并发态翻转）归还票据再拒绝——票据不被本次失败消费，轮询面不受影响。
     *
     * @param ticketId  票据 ID
     * @param clientIp  客户端 IP（登录日志/会话信息富化）
     * @param userAgent 客户端 UA（登录日志）
     * @return 确认者 B 端令牌（与轮询返回值同一签发结果）
     */
    public AdminTokenIssued confirm(String ticketId, String clientIp, String userAgent) {
        // 1. 确认主体（内部令牌链路绑定：UserContext/B 端确认者；TenantContext/B 端租户）——缺失即未认证
        UserContext current = UserContext.get();
        TenantContext tenant = TenantContext.get();
        if (current == null || current.userId() == null || current.userId().isBlank()
                || tenant == null || tenant.tenantId() == null || tenant.tenantId().isBlank()) {
            throw new BizException(SecurityError.UNAUTHORIZED);
        }
        Long userId = Long.parseLong(current.userId());
        // 2. 预检（只读快速失败）：confirmed/cancelled 在取票前拒绝——票据绝不因注定失败的确认被消费
        Optional<QrTicket> precheck = qrTicketStore.read(ticketId);
        if (precheck.isEmpty()) {
            throw new BizException(AuthError.QR_TICKET_EXPIRED);
        }
        String preStatus = precheck.get().status();
        if (QrTicketStore.STATUS_CONFIRMED.equals(preStatus)) {
            throw new BizException(AuthError.QR_TICKET_INVALID);
        }
        if (QrTicketStore.STATUS_CANCELLED.equals(preStatus)) {
            throw new BizException(AuthError.QR_TICKET_CANCELLED);
        }
        // 3. 原子取票（Lua GET+DEL 单次消费）＋取票后状态权威校验（并发窗口内态翻转兜底）
        Optional<QrTicket> taken = qrTicketStore.take(ticketId);
        if (taken.isEmpty()) {
            throw new BizException(AuthError.QR_TICKET_EXPIRED);
        }
        QrTicket ticket = taken.get();
        long remainingSeconds = remainingTtlSeconds(ticket.createdAt());
        if (!QrTicketStore.STATUS_CREATED.equals(ticket.status())
                && !QrTicketStore.STATUS_SCANNED.equals(ticket.status())) {
            // 确认窗口内票据被并发终态化（cancelled）或已 confirmed：归还后按态拒绝
            qrTicketStore.save(ticketId, ticket, Duration.ofSeconds(remainingSeconds));
            throw mapUnconfirmableStatus(ticket.status());
        }
        // 4. 画像先行（远程调用置于会话面变更之前——失败不互踢不改底账，票归还重试）
        //    → 互踢（B 端单会话 Q-003）→ 签发 → 会话底账 + 单会话索引 + 会话信息（LoginUseCase 第 7-9 步口径）
        AdminTokenIssued issued;
        try {
            AuthProfileView profile = resolveProfile(userId);
            kickPreviousSessions(userId);
            UserTokenIssuer.IssuedToken token = tokenIssuer.issue(userId, tenant.tenantId(), current.roles());
            sessionRecorder.record(token.tokenId(), userId, tenant.tenantId(), UserTokenIssuer.CHANNEL_B,
                    token.issuedAt(), token.expiresAt());
            long ttl = Math.max(1, Duration.between(Instant.now(), token.expiresAt()).toSeconds());
            sessionRedis.indexSession(userId, token.tokenId(), ttl);
            sessionRedis.writeSessionInfo(token.tokenId(), clientIp, token.issuedAt(), ttl);
            issued = new AdminTokenIssued(token.token(), "Bearer", token.expiresAt(), userId,
                    profile.username(), profile.nickname());
        } catch (RuntimeException ex) {
            // 签发链路失败：票据归还为 scanned（APP 可重试；轮询面不受本次失败影响）
            log.warn("扫码确认签发链路失败，票据归还 scanned：ticketId={} userId={}", ticketId, userId, ex);
            qrTicketStore.save(ticketId, ticket.asScanned(), Duration.ofSeconds(remainingSeconds));
            throw ex;
        }
        // 5. 票据回写 confirmed＋token（剩余 TTL；TTL 内轮询可重复取同一令牌）——不因取票丢失轮询面
        qrTicketStore.save(ticketId, new QrTicket(QrTicketStore.STATUS_CONFIRMED, ticket.createdAt(),
                ticket.createdIp(), current.userId(), issued.username(), issued),
                Duration.ofSeconds(remainingSeconds));
        // 6. 登录日志（channel B 成功，AC-3；@Async 异步落库，写失败不影响本路径）
        loginLogRecorder.record(LoginLogRecorder.Entry.capture(issued.username(), UserTokenIssuer.CHANNEL_B,
                clientIp, userAgent, true, null));
        return issued;
    }

    /**
     * 取消票据（B 端认证，幂等）：CAS 置 cancelled（仅 created/scanned 可取消，确认中/已确认不覆盖）；
     * 缺失/已终态一律成功（幂等语义，确认记录④ Result&lt;Void&gt;）。
     */
    public void cancel(String ticketId) {
        qrTicketStore.cancel(ticketId);
    }

    /** 取票后状态→业务失败码映射（confirmed/未知脏值 → INVALID；cancelled → CANCELLED）。 */
    private BizException mapUnconfirmableStatus(String status) {
        if (QrTicketStore.STATUS_CANCELLED.equals(status)) {
            return new BizException(AuthError.QR_TICKET_CANCELLED);
        }
        return new BizException(AuthError.QR_TICKET_INVALID);
    }

    /** 票据剩余 TTL（秒）：createdAt + 120s - now（下限 0，回写时由 save 收敛为至少 1s）。 */
    private long remainingTtlSeconds(String createdAtIso) {
        try {
            Instant createdAt = Instant.parse(createdAtIso);
            return Math.max(Duration.between(Instant.now(), createdAt.plus(QrTicketStore.DEFAULT_TTL)).toSeconds(),
                    0);
        } catch (RuntimeException ex) {
            log.warn("票据 createdAt 解析失败，剩余 TTL 按 0 处理：createdAt={}", createdAtIso);
            return 0;
        }
    }

    /** 展示主体（username/nickname 富化，复用既有批量画像查询；缺失按空串兜底不阻断确认）。 */
    private AuthProfileView resolveProfile(Long userId) {
        Map<Long, AuthProfileView> profiles = authProfileGateway.findByUserIds(List.of(userId));
        AuthProfileView profile = profiles.get(userId);
        if (profile == null) {
            log.warn("确认主体画像缺失（展示字段缺省）：userId={}", userId);
            return new AuthProfileView(String.valueOf(userId), "", null, null, "ENABLED", Set.of());
        }
        return profile;
    }

    /** 互踢：旧活跃会话置 KICKED（事务）→ 撤销集写入（事务外，失败降级 WARN 不阻断确认）。 */
    private void kickPreviousSessions(Long userId) {
        List<SessionRecorder.RevocableToken> previous = sessionRecorder.kickActiveSessions(userId);
        SessionRevocations.bestEffort(sessionRedis, previous, userId, "扫码确认互踢");
    }
}
