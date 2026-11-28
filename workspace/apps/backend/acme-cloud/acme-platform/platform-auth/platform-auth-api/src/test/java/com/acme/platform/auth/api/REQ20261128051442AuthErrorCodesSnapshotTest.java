package com.acme.platform.auth.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261128-051442（AC-11）：AuthErrorCodes 全量常量快照测试（非 MSW）。
 * 契约附录 A.4 为唯一权威清单：反射枚举全部 public static final String 常量（按名排序），
 * 断言 (name→value) 全集精确等于附录 A auth 切片——双向锁：缺常量、多常量、任一值漂移皆红。
 * 决策①：后端 Result.code() 与一切错误码定义零改动，本测试仅锁定现状 wire 真值。
 */
class REQ20261128051442AuthErrorCodesSnapshotTest {

    /** 附录 A.4 切片（platform-auth 段，wire 值 ← 常量名，21 码）。 */
    private static final Map<String, String> CONTRACT_APPENDIX_A_AUTH = Map.ofEntries(
            // ---- 01 会话 ----
            Map.entry("LOGIN_FAILED", "platform-auth-01-0001"),
            Map.entry("ACCOUNT_LOCKED", "platform-auth-01-0002"),
            Map.entry("ACCOUNT_DISABLED", "platform-auth-01-0003"),
            Map.entry("LOGOUT_FAILED", "platform-auth-01-0004"),
            // ---- 02 凭证 ----
            Map.entry("CREDENTIAL_NOT_INITIALIZED", "platform-auth-02-0001"),
            Map.entry("PASSWORD_POLICY_VIOLATED", "platform-auth-02-0002"),
            // ---- 03 验证码/令牌 ----
            Map.entry("CAPTCHA_SEND_LIMITED", "platform-auth-03-0001"),
            Map.entry("CAPTCHA_INVALID", "platform-auth-03-0002"),
            Map.entry("CAPTCHA_EXPIRED", "platform-auth-03-0003"),
            Map.entry("CONSUMER_DISABLED", "platform-auth-03-0004"),
            Map.entry("REFRESH_TOKEN_INVALID", "platform-auth-03-0005"),
            // ---- 04 在线会话 ----
            Map.entry("SESSION_NOT_FOUND", "platform-auth-04-0001"),
            Map.entry("REVOKE_SUPERADMIN_FORBIDDEN", "platform-auth-04-0002"),
            // ---- 05 租户切换 ----
            Map.entry("TENANT_SWITCH_FORBIDDEN", "platform-auth-05-0001"),
            Map.entry("TENANT_SWITCH_FAILED", "platform-auth-05-0002"),
            // ---- 06 扫码 ----
            Map.entry("QR_TICKET_LIMITED", "platform-auth-06-0001"),
            Map.entry("QR_TICKET_EXPIRED", "platform-auth-06-0002"),
            Map.entry("QR_TICKET_CANCELLED", "platform-auth-06-0003"),
            Map.entry("QR_TICKET_INVALID", "platform-auth-06-0004"),
            // ---- 07 改密 ----
            Map.entry("OLD_PASSWORD_INVALID", "platform-auth-07-0001"),
            Map.entry("SAME_PASSWORD", "platform-auth-07-0002"));

    @Test
    void authErrorCodesConstantsExactlyMatchContractAppendixA() throws IllegalAccessException {
        assertThat(publicStringConstants(AuthErrorCodes.class))
                .containsExactlyInAnyOrderEntriesOf(CONTRACT_APPENDIX_A_AUTH);
    }

    /** 反射枚举目标类全部 public static final String 常量（按名排序，值为 wire 真值）。 */
    private static Map<String, String> publicStringConstants(Class<?> type) throws IllegalAccessException {
        Map<String, String> constants = new TreeMap<>();
        for (Field field : type.getDeclaredFields()) {
            boolean isPublicStaticFinalString = field.getType() == String.class
                    && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers());
            if (isPublicStaticFinalString) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        return constants;
    }
}
