package com.acme.platform.auth.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-auth-api 契约测试（16.1，本地可执行口径）：DTO 形态稳定与预留枚举锁定（架构设计 9.2）。
 */
class AuthApiContractTest {

    @Test
    void tokenIssuedShapeLocked() {
        AdminTokenIssued issued = new AdminTokenIssued("token-value", null,
                Instant.parse("2026-10-22T08:30:00Z"), 1234567890123456789L, "admin", "超级管理员");
        // tokenType 缺省归一 Bearer（openapi 契约枚举唯一值）
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.accessToken()).isEqualTo("token-value");
        assertThat(issued.userId()).isEqualTo(1234567890123456789L);
    }

    @Test
    void reservedTokenTypeWireNamesLocked() {
        // 与网关 tokenType claim / framework-security TokenType 协议名对齐
        assertThat(AuthTokenType.ACCESS.wireName()).isEqualTo("access");
        assertThat(AuthTokenType.REFRESH.wireName()).isEqualTo("refresh");
    }

    @Test
    void errorCodesLayoutLocked() {
        // 三段式结构（ErrorCodeLayout 分配规则：serviceId=platform-auth，模块段 2 位）
        assertThat(AuthErrorCodes.LOGIN_FAILED).isEqualTo("platform-auth-01-0001");
        assertThat(AuthErrorCodes.ACCOUNT_LOCKED).isEqualTo("platform-auth-01-0002");
        assertThat(AuthErrorCodes.ACCOUNT_DISABLED).isEqualTo("platform-auth-01-0003");
        assertThat(AuthErrorCodes.LOGOUT_FAILED).isEqualTo("platform-auth-01-0004");
        assertThat(AuthErrorCodes.CREDENTIAL_NOT_INITIALIZED).isEqualTo("platform-auth-02-0001");
        assertThat(AuthErrorCodes.PASSWORD_POLICY_VIOLATED).isEqualTo("platform-auth-02-0002");
        // 03 段：C 端消费者认证域（consumer-auth-refresh-dataperm）
        assertThat(AuthErrorCodes.CAPTCHA_SEND_LIMITED).isEqualTo("platform-auth-03-0001");
        assertThat(AuthErrorCodes.CAPTCHA_INVALID).isEqualTo("platform-auth-03-0002");
        assertThat(AuthErrorCodes.CAPTCHA_EXPIRED).isEqualTo("platform-auth-03-0003");
        assertThat(AuthErrorCodes.CONSUMER_DISABLED).isEqualTo("platform-auth-03-0004");
        assertThat(AuthErrorCodes.REFRESH_TOKEN_INVALID).isEqualTo("platform-auth-03-0005");
        // 05 段：超管跨租户切换（REQ-20261117-025200 确认记录④）
        assertThat(AuthErrorCodes.TENANT_SWITCH_FORBIDDEN).isEqualTo("platform-auth-05-0001");
        assertThat(AuthErrorCodes.TENANT_SWITCH_FAILED).isEqualTo("platform-auth-05-0002");
        // 06 段：APP 扫码登录票据域（REQ-20261119-040838 确认记录③④）
        assertThat(AuthErrorCodes.QR_TICKET_LIMITED).isEqualTo("platform-auth-06-0001");
        assertThat(AuthErrorCodes.QR_TICKET_EXPIRED).isEqualTo("platform-auth-06-0002");
        assertThat(AuthErrorCodes.QR_TICKET_CANCELLED).isEqualTo("platform-auth-06-0003");
        assertThat(AuthErrorCodes.QR_TICKET_INVALID).isEqualTo("platform-auth-06-0004");
    }

    @Test
    void qrTicketViewsShapeLocked() {
        // REQ-20261119-040838 §API：创建/轮询/扫码取信息三视图形状锁定
        QrTicketCreatedView created = new QrTicketCreatedView("ticket-1", "{\"t\":\"ticket-1\"}", 120);
        assertThat(created.ticketId()).isEqualTo("ticket-1");
        assertThat(created.qrPayload()).isEqualTo("{\"t\":\"ticket-1\"}");
        assertThat(created.expiresInSeconds()).isEqualTo(120);

        AdminTokenIssued confirmedToken = new AdminTokenIssued("token-value", null,
                Instant.parse("2026-11-19T08:30:00Z"), 1234567890123456789L, "admin", "超级管理员");
        QrTicketStatusView confirmed = new QrTicketStatusView("confirmed", confirmedToken);
        assertThat(confirmed.status()).isEqualTo("confirmed");
        assertThat(confirmed.token()).isSameAs(confirmedToken);
        // 非 confirmed 态 token 为 null（确认记录④：仅 confirmed 携令牌）
        assertThat(new QrTicketStatusView("expired", null).token()).isNull();
        assertThat(new QrTicketStatusView("scanned", null).status()).isEqualTo("scanned");

        QrTicketInfoView info = new QrTicketInfoView("scanned", "2026-11-19T08:28:00Z");
        assertThat(info.status()).isEqualTo("scanned");
        assertThat(info.createdAt()).isEqualTo("2026-11-19T08:28:00Z");
    }

    @Test
    void tenantSessionRevokeClientContractMetadataLocked() throws Exception {
        // REQ-20261117-025200 确认记录②：撤销契约声明于本模块（提供方 auth，消费方 system），
        // Feign 元数据锁定；信封复用 platform-system-api ContractResult（LoginLogCleanupClient 先例）
        org.springframework.cloud.openfeign.FeignClient feignClient =
                TenantSessionRevokeClient.class.getAnnotation(
                        org.springframework.cloud.openfeign.FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("platform-auth");
        assertThat(feignClient.contextId()).isEqualTo("tenantSessionRevokeClient");

        var method = TenantSessionRevokeClient.class.getMethod("revokeByTenant",
                TenantSessionRevokeRequest.class);
        var postMapping = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertThat(postMapping).isNotNull();
        assertThat(postMapping.value()).containsExactly("/internal/v1/tenant-sessions/revocations");
        assertThat(method.getReturnType()).isEqualTo(
                com.acme.platform.system.api.ContractResult.class);
        // 计数字段 int（wire 面向服务间，不经 Long→String 序列化）
        var view = new TenantSessionRevokeView(2, 1);
        assertThat(view.revokedCount()).isEqualTo(2);
        assertThat(view.failedCount()).isEqualTo(1);
        assertThat(TenantSessionRevokeView.empty()).isEqualTo(new TenantSessionRevokeView(0, 0));
        // 公开站点配置视图（确认记录⑤）：仅页脚两字段 + 缺省视图
        assertThat(TenantPublicConfigView.defaultValue())
                .isEqualTo(new TenantPublicConfigView(null, false));
    }

    @Test
    void appTokenIssuedShapeLocked() {
        java.time.Instant accessExp = java.time.Instant.parse("2026-10-26T10:00:00Z");
        java.time.Instant refreshExp = java.time.Instant.parse("2026-11-09T10:00:00Z");
        com.acme.platform.auth.api.dto.AppTokenIssued issued = new com.acme.platform.auth.api.dto.AppTokenIssued(
                "access-token", "refresh-token", accessExp, refreshExp, 1234567890123456789L, "13800138000");
        assertThat(issued.accessToken()).isEqualTo("access-token");
        assertThat(issued.refreshToken()).isEqualTo("refresh-token");
        assertThat(issued.refreshExpiresAt()).isAfter(issued.accessExpiresAt());
    }
}
