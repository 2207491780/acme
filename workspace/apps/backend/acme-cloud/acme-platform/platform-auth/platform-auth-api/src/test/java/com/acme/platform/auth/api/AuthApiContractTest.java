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
