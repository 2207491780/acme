package com.acme.platform.auth.api;

import com.acme.common.serialization.JsonModules;
import com.acme.platform.auth.api.dto.AppTokenIssued;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261127-234158（P1-06）：AppTokenIssued wire 契约测试（真实 JsonModules.standard() mapper
 * 断言 writeValueAsString/readValue 产物，SystemApiContractTest 先例口径，非 MSW）。
 * 锁定：字段名 accessExpiresAt/refreshExpiresAt（C 端历史短名 expiresAt 不得出现）、
 * userId Long→JSON 字符串（LongToStringModule 全局）、Instant ISO-8601；回读 userId 保持 Long 值。
 */
class REQ20261127234158AppTokenIssuedWireTest {

    private final JsonMapper mapper = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        return builder.build();
    }

    @Test
    void appTokenIssuedSerializesAccessRefreshExpiresAtNamesAndStringUserId() {
        AppTokenIssued issued = new AppTokenIssued("access-token-value", "refresh-token-value",
                Instant.parse("2026-10-26T10:00:00Z"), Instant.parse("2026-11-09T10:00:00Z"),
                1234567890123456789L, "13800138000");
        String json = mapper.writeValueAsString(issued);
        // 字段名锁定（AC-3 golden；mobile AppTokenIssued 声明的事实源）：token 双字段 + 过期双长名
        assertThat(json).contains("\"accessToken\":\"access-token-value\"");
        assertThat(json).contains("\"refreshToken\":\"refresh-token-value\"");
        assertThat(json).contains("\"accessExpiresAt\":\"2026-10-26T10:00:00Z\"");
        assertThat(json).contains("\"refreshExpiresAt\":\"2026-11-09T10:00:00Z\"");
        // Long→JSON 字符串（LongToStringModule 全局；userId 以 string 声明进 mobile 契约）
        assertThat(json).contains("\"userId\":\"1234567890123456789\"");
        assertThat(json).contains("\"phone\":\"13800138000\"");
        // 短名负例：带前引号的 "expiresAt": 不得出现（"accessExpiresAt": 不含该子串，断言安全）
        assertThat(json).doesNotContain("\"expiresAt\":");
        // 回读：userId 保持 Long 值（字符串口径反序列化，LongToStringSerializationTest ③ 双口径先例）
        AppTokenIssued back = mapper.readValue(json, AppTokenIssued.class);
        assertThat(back.userId()).isEqualTo(1234567890123456789L);
        assertThat(back.accessToken()).isEqualTo("access-token-value");
        assertThat(back.refreshToken()).isEqualTo("refresh-token-value");
        assertThat(back.accessExpiresAt()).isEqualTo(Instant.parse("2026-10-26T10:00:00Z"));
        assertThat(back.refreshExpiresAt()).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
        assertThat(back.phone()).isEqualTo("13800138000");
    }
}
