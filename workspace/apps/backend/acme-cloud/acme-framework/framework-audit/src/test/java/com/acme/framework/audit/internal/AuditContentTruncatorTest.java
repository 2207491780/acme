package com.acme.framework.audit.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计内容截取与脱敏测试（FR-010/AC-010、BR-013）。
 */
class AuditContentTruncatorTest {

    @Test
    void masksSensitiveValues() {
        String masked = AuditContentTruncator.apply("name=alice, password=secret123, token=abc", 512, true);
        assertThat(masked).contains("name=alice");
        assertThat(masked).contains("password=***");
        assertThat(masked).contains("token=***");
        assertThat(masked).doesNotContain("secret123");
    }

    @Test
    void truncatesToMaxLength() {
        String longContent = "name=" + "x".repeat(1000);
        assertThat(AuditContentTruncator.apply(longContent, 10, true)).hasSize(10);
    }

    @Test
    void maskDisabledKeepsRawContent() {
        assertThat(AuditContentTruncator.apply("password=p", 512, false)).contains("p");
    }

    @Test
    void sensitiveKeyDetection() {
        assertThat(AuditContentTruncator.isSensitiveKey("accessKey")).isTrue();
        assertThat(AuditContentTruncator.isSensitiveKey("name")).isFalse();
    }

    // ---------- REQ-20261127-161433 P2-21：before 旧值 JSON 递归脱敏 ----------

    @Test
    void maskJsonMasksSensitiveKeysAtNestedDepth() {
        JsonMapper mapper = JsonMapper.builder().build();
        String json = "{\"name\":\"张三\",\"credential\":{\"accessToken\":\"abc\",\"nested\":"
                + "[{\"api_key\":\"xyz\"}]},\"password\":\"p@ss\"}";

        String masked = AuditContentTruncator.maskJson(json, mapper);

        assertThat(masked)
                // 敏感键值统一掩码：credential 子树整体掩码（父键命中即不透传子结构，
                // accessToken 连同嵌套数组不再出现）；非敏感兄弟键 name 保留
                .contains("\"credential\":\"***\"")
                .contains("\"password\":\"***\"")
                .doesNotContain("abc").doesNotContain("xyz").doesNotContain("p@ss")
                .doesNotContain("accessToken").doesNotContain("api_key");
        assertThat(masked).contains("\"name\":\"张三\"");
    }

    @Test
    void maskJsonFailsOpenForMalformedOrNonJsonContent() {
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(AuditContentTruncator.maskJson("not json {broken", mapper))
                .isEqualTo("not json {broken");
        assertThat(AuditContentTruncator.maskJson("plain key=value", mapper))
                .isEqualTo("plain key=value");
        assertThat(AuditContentTruncator.maskJson("{\"ok\":1}", null)).isEqualTo("{\"ok\":1}");
    }

    @Test
    void maskJsonHandlesArraysOfObjectsAtTopLevel() {
        JsonMapper mapper = JsonMapper.builder().build();
        String masked = AuditContentTruncator.maskJson(
                "[{\"refreshToken\":\"r-1\"},{\"remark\":\"ok\"}]", mapper);
        assertThat(masked).contains("\"refreshToken\":\"***\"").contains("\"remark\":\"ok\"");
    }
}
