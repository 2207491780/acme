package com.acme.platform.auth.application;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.auth.AuthError;
import com.acme.platform.auth.api.AdminTokenIssued;
import com.acme.platform.auth.api.QrTicketCreatedView;
import com.acme.platform.auth.api.QrTicketStatusView;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.infrastructure.client.AuthProfileGateway;
import com.acme.platform.auth.infrastructure.redis.QrTicketStore;
import com.acme.platform.auth.infrastructure.redis.QrTicketStore.QrTicket;
import com.acme.platform.auth.infrastructure.redis.SessionRedis;
import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.system.api.AuthProfileView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 扫码登录用例测试（REQ-20261119-040838，纯 JUnit 无 Spring）：
 * 状态机映射（expired/cancelled/invalid/幂等 scanned）、创建频控（QR_TICKET_LIMITED 429）、
 * 确认原子性口径（预检不消费票、取票后并发态翻转归还、签发失败归还 scanned 重试）与
 * 确认全链编排（互踢/底账/索引/回写 confirmed＋token/登录日志）。
 */
class QrLoginUseCaseTest {

    private static final String SECRET = "qr-login-test-secret-0123456789abcdef";

    private final QrTicketStore qrTicketStore = mock(QrTicketStore.class);
    private final SessionRecorder sessionRecorder = mock(SessionRecorder.class);
    private final SessionRedis sessionRedis = mock(SessionRedis.class);
    private final AuthProfileGateway authProfileGateway = mock(AuthProfileGateway.class);
    private final LoginLogRecorder loginLogRecorder = mock(LoginLogRecorder.class);

    private QrLoginUseCase useCase;

    @BeforeEach
    void setUp() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setTtlSeconds(1800);
        useCase = new QrLoginUseCase(qrTicketStore, new UserTokenIssuer(properties), sessionRecorder,
                sessionRedis, authProfileGateway, loginLogRecorder, JsonMapper.builder().build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
    }

    private void bindConfirmer(String userId, String tenantId, Set<String> roles) {
        UserContext.bind(new UserContext(userId, roles));
        TenantContext.bind(new TenantContext(tenantId));
    }

    private void stubProfile() {
        when(authProfileGateway.findByUserIds(any())).thenReturn(Map.of(
                1001L, new AuthProfileView("1001", "admin", "超级管理员", "default", "ENABLED",
                        Set.of("super_admin"))));
    }

    private static QrTicket ticket(String status) {
        return new QrTicket(status, Instant.now().toString(), "10.0.0.1", null, null, null);
    }

    // ---------- 创建：频控 + payload ----------

    @Test
    void should_rejectWith429Limited_when_creationQuotaExceeded() {
        // AC-1：同 IP 第 11 次/分钟 → QR_TICKET_LIMITED（429 限流语义）；不产生票据
        when(qrTicketStore.tryAcquireCreationQuota("10.0.0.1")).thenReturn(false);

        assertThatThrownBy(() -> useCase.create("10.0.0.1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    assertThat(((BizException) ex).errorCode().code())
                            .isEqualTo(AuthError.QR_TICKET_LIMITED.code());
                    assertThat(((HttpStatusAware) ((BizException) ex).errorCode()).httpStatus())
                            .as("频控 429 限流语义（确认记录④）").isEqualTo(429);
                });
        verify(qrTicketStore, never()).create(anyString());
    }

    @Test
    void should_createTicketWithCompactPayload_when_quotaAvailable() {
        // AC-1 前半：创建成功返回 ticketId / qrPayload={"t":ticketId} / expiresInSeconds=120
        when(qrTicketStore.tryAcquireCreationQuota("10.0.0.1")).thenReturn(true);
        when(qrTicketStore.create("10.0.0.1")).thenReturn("abc123");

        QrTicketCreatedView view = useCase.create("10.0.0.1");

        assertThat(view.ticketId()).isEqualTo("abc123");
        assertThat(view.qrPayload()).as("二维码内容紧凑 JSON（确认记录⑤）").isEqualTo("{\"t\":\"abc123\"}");
        assertThat(view.expiresInSeconds()).as("TTL 120s（确认记录③）").isEqualTo(120);
    }

    // ---------- 轮询：只读 + 过期兜底 ----------

    @Test
    void should_pollExpiredWithNullToken_when_ticketMissing() {
        // AC-5：未知/过期 id（TTL 兜底）→ expired + token null，不泄露存在性差异
        when(qrTicketStore.read("missing")).thenReturn(Optional.empty());

        QrTicketStatusView view = useCase.poll("missing");

        assertThat(view.status()).isEqualTo("expired");
        assertThat(view.token()).isNull();
    }

    @Test
    void should_pollConfirmedWithToken_when_ticketConfirmed() {
        // AC-4：轮询 confirmed → token 非空（TTL 内可重复取，不因轮询失效）
        AdminTokenIssued token = new AdminTokenIssued("jwt-value", null,
                Instant.now().plusSeconds(600), 1001L, "admin", "超级管理员");
        when(qrTicketStore.read("t1")).thenReturn(Optional.of(new QrTicket(
                QrTicketStore.STATUS_CONFIRMED, Instant.now().toString(), "10.0.0.1",
                "1001", "admin", token)));

        QrTicketStatusView view = useCase.poll("t1");

        assertThat(view.status()).isEqualTo("confirmed");
        assertThat(view.token()).isSameAs(token);
    }

    @Test
    void should_pollIntermediateStateWithoutToken_when_ticketScanned() {
        when(qrTicketStore.read("t1")).thenReturn(Optional.of(ticket(QrTicketStore.STATUS_SCANNED)));

        QrTicketStatusView view = useCase.poll("t1");

        assertThat(view.status()).isEqualTo("scanned");
        assertThat(view.token()).as("非 confirmed 态 token 恒为 null（确认记录④）").isNull();
    }

    // ---------- 扫码取信息：幂等 + 业务失败码 ----------

    @Test
    void should_rejectExpired_when_infoOnMissingTicket() {
        // AC-2/AC-5：未知 id info → QR_TICKET_EXPIRED（业务失败信封）
        when(qrTicketStore.markScanned("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.info("missing"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.QR_TICKET_EXPIRED.code()));
    }

    @Test
    void should_rejectCancelled_when_infoOnCancelledTicket() {
        // AC-5：已取消票 info → QR_TICKET_CANCELLED
        when(qrTicketStore.markScanned("t1")).thenReturn(Optional.of(ticket(QrTicketStore.STATUS_CANCELLED)));

        assertThatThrownBy(() -> useCase.info("t1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.QR_TICKET_CANCELLED.code()));
    }

    @Test
    void should_returnScannedIdempotently_when_infoRepeated() {
        // AC-2：created→scanned 幂等（CAS 后 store 返回快照，用例无二次写）
        QrTicket scanned = new QrTicket(QrTicketStore.STATUS_SCANNED, "2026-11-19T08:28:00Z",
                "10.0.0.1", null, null, null);
        when(qrTicketStore.markScanned("t1")).thenReturn(Optional.of(scanned));

        var view = useCase.info("t1");

        assertThat(view.status()).isEqualTo("scanned");
        assertThat(view.createdAt()).isEqualTo("2026-11-19T08:28:00Z");
        verify(qrTicketStore, never()).save(anyString(), any(QrTicket.class), any(Duration.class));
    }

    @Test
    void should_notRegressConfirmed_when_infoOnConfirmedTicket() {
        // 确认记录③：重复 info 不回退 confirmed（CAS 原样返回）
        when(qrTicketStore.markScanned("t1")).thenReturn(Optional.of(ticket(QrTicketStore.STATUS_CONFIRMED)));

        var view = useCase.info("t1");

        assertThat(view.status()).isEqualTo("confirmed");
    }

    // ---------- 确认：上下文 / 状态机 / 原子性 / 全链编排 ----------

    @Test
    void should_rejectUnauthorized_when_confirmerContextMissing() {
        // 防御：内部令牌链路未绑定上下文（网关链路必绑定）→ UNAUTHORIZED
        assertThatThrownBy(() -> useCase.confirm("t1", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(com.acme.framework.security.support.SecurityError.UNAUTHORIZED.code()));
        verify(qrTicketStore, never()).take(anyString());
    }

    @Test
    void should_rejectExpired_when_confirmOnMissingTicket() {
        bindConfirmer("1001", "default", Set.of("super_admin"));
        when(qrTicketStore.read("t1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.confirm("t1", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.QR_TICKET_EXPIRED.code()));
        verify(qrTicketStore, never()).take(anyString());
    }

    @Test
    void should_rejectInvalidWithoutConsuming_when_confirmOnConfirmedTicket() {
        // AC-4：二次 confirm → QR_TICKET_INVALID；预检短路——票据不被取票消费（轮询面可重复取 token）
        bindConfirmer("1001", "default", Set.of("super_admin"));
        when(qrTicketStore.read("t1")).thenReturn(Optional.of(ticket(QrTicketStore.STATUS_CONFIRMED)));

        assertThatThrownBy(() -> useCase.confirm("t1", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.QR_TICKET_INVALID.code()));
        verify(qrTicketStore, never()).take(anyString());
        verify(qrTicketStore, never()).save(anyString(), any(QrTicket.class), any(Duration.class));
        verify(sessionRecorder, never()).kickActiveSessions(anyLong());
    }

    @Test
    void should_rejectCancelled_when_confirmOnCancelledTicket() {
        // AC-5：已取消票 confirm → QR_TICKET_CANCELLED
        bindConfirmer("1001", "default", Set.of("super_admin"));
        when(qrTicketStore.read("t1")).thenReturn(Optional.of(ticket(QrTicketStore.STATUS_CANCELLED)));

        assertThatThrownBy(() -> useCase.confirm("t1", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.QR_TICKET_CANCELLED.code()));
        verify(qrTicketStore, never()).take(anyString());
    }

    @Test
    void should_restoreTicketAndReject_when_stateFlipsAfterPrecheck() {
        // 原子性：预检 created → 取票拿到并发终态（confirmed）→ 归还票据后 INVALID（票据不被本次失败消费）
        bindConfirmer("1001", "default", Set.of("super_admin"));
        QrTicket confirmed = ticket(QrTicketStore.STATUS_CONFIRMED);
        when(qrTicketStore.read("t1")).thenReturn(Optional.of(ticket(QrTicketStore.STATUS_CREATED)));
        when(qrTicketStore.take("t1")).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> useCase.confirm("t1", "10.0.0.1", "JUnit"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(AuthError.QR_TICKET_INVALID.code()));
        ArgumentCaptor<QrTicket> restored = ArgumentCaptor.forClass(QrTicket.class);
        verify(qrTicketStore).save(eq("t1"), restored.capture(), any(Duration.class));
        assertThat(restored.getValue().status()).as("归还的是并发终态原样").isEqualTo(QrTicketStore.STATUS_CONFIRMED);
        verify(sessionRecorder, never()).kickActiveSessions(anyLong());
    }

    @Test
    void should_issueTokenAndWriteBackConfirmed_when_confirmOnScannedTicket() {
        // AC-3：scanned 票 confirm → 互踢/签发/底账/索引/信息/日志全链 + confirmed＋token 回写（剩余 TTL）
        bindConfirmer("1001", "default", Set.of("super_admin"));
        stubProfile();
        QrTicket scanned = new QrTicket(QrTicketStore.STATUS_SCANNED, Instant.now().toString(),
                "10.0.0.1", null, null, null);
        when(qrTicketStore.read("t1")).thenReturn(Optional.of(scanned));
        when(qrTicketStore.take("t1")).thenReturn(Optional.of(scanned));
        when(sessionRecorder.kickActiveSessions(1001L)).thenReturn(List.of());

        AdminTokenIssued issued = useCase.confirm("t1", "10.0.0.1", "JUnit");

        // 返回 AdminTokenIssued（确认者本人，userId=1001）
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.userId()).isEqualTo(1001L);
        assertThat(issued.username()).isEqualTo("admin");
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        // 全链编排：互踢 → 底账（channel B）→ 索引 → 会话信息 → 登录日志（channel B 成功）
        verify(sessionRecorder).kickActiveSessions(1001L);
        verify(sessionRecorder).record(anyString(), eq(1001L), eq("default"),
                eq(UserTokenIssuer.CHANNEL_B), any(Instant.class), any(Instant.class));
        verify(sessionRedis).indexSession(eq(1001L), anyString(), anyLong());
        verify(sessionRedis).writeSessionInfo(anyString(), eq("10.0.0.1"), any(Instant.class), anyLong());
        ArgumentCaptor<LoginLogRecorder.Entry> logEntry = ArgumentCaptor.forClass(LoginLogRecorder.Entry.class);
        verify(loginLogRecorder).record(logEntry.capture());
        assertThat(logEntry.getValue().userType()).isEqualTo("B");
        assertThat(logEntry.getValue().username()).isEqualTo("admin");
        // 回写 confirmed＋token（TTL 内轮询可重复取）
        ArgumentCaptor<QrTicket> writeBack = ArgumentCaptor.forClass(QrTicket.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(qrTicketStore).save(eq("t1"), writeBack.capture(), ttl.capture());
        assertThat(writeBack.getValue().status()).isEqualTo(QrTicketStore.STATUS_CONFIRMED);
        assertThat(writeBack.getValue().confirmUserId()).isEqualTo("1001");
        assertThat(writeBack.getValue().confirmUsername()).isEqualTo("admin");
        assertThat(writeBack.getValue().token()).isNotNull();
        assertThat(writeBack.getValue().token().accessToken()).isEqualTo(issued.accessToken());
        assertThat(ttl.getValue().toSeconds()).as("剩余 TTL（120s 内）").isBetween(1L, 120L);
        // 回写令牌 JWS 形状：channel=B / tokenType=access / sub=确认者（AC-4 校验同一签发结果）
        var verified = new UserTokenIssuer(properties()).verify(issued.accessToken());
        assertThat(verified.channel()).isEqualTo(UserTokenIssuer.CHANNEL_B);
        assertThat(verified.tokenType()).isEqualTo(UserTokenIssuer.TOKEN_TYPE_ACCESS);
    }

    @Test
    void should_restoreScannedAndRethrow_when_issueChainFails() {
        // 原子性：签发链路失败 → 票据归还 scanned（APP 可重试）→ 异常上抛
        bindConfirmer("1001", "default", Set.of("super_admin"));
        stubProfile();
        QrTicket scanned = ticket(QrTicketStore.STATUS_SCANNED);
        when(qrTicketStore.read("t1")).thenReturn(Optional.of(scanned));
        when(qrTicketStore.take("t1")).thenReturn(Optional.of(scanned));
        when(sessionRecorder.kickActiveSessions(1001L)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(sessionRecorder).record(anyString(), anyLong(), anyString(), anyString(),
                        any(Instant.class), any(Instant.class));

        assertThatThrownBy(() -> useCase.confirm("t1", "10.0.0.1", "JUnit"))
                .isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<QrTicket> restored = ArgumentCaptor.forClass(QrTicket.class);
        verify(qrTicketStore).save(eq("t1"), restored.capture(), any(Duration.class));
        assertThat(restored.getValue().status()).as("失败归还 scanned").isEqualTo(QrTicketStore.STATUS_SCANNED);
        assertThat(restored.getValue().token()).as("归还态不含令牌").isNull();
        verify(loginLogRecorder, never()).record(any());
    }

    private static AuthTokenProperties properties() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setHmacSecret(SECRET);
        properties.setTtlSeconds(1800);
        return properties;
    }

    // ---------- 取消：幂等委托 ----------

    @Test
    void should_delegateCancelIdempotently() {
        // 取消幂等（确认记录④ Result<Void>）：缺失/已终态由 store CAS 收敛，用例一律成功
        useCase.cancel("t1");
        verify(qrTicketStore).cancel("t1");
    }
}
