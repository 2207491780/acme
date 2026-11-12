package com.acme.framework.audit.internal;

import org.junit.jupiter.api.Test;

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
}
