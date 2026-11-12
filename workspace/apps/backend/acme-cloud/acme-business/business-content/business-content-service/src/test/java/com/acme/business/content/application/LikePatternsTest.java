package com.acme.business.content.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIKE 通配符转义契约（BR-005/BR-012 关键字按字面匹配，防通配注入）：
 * search 与 suggestions 共用本口径，%、_、\ 须转义，trim 后小写。
 */
class LikePatternsTest {

    @Test
    void escapesPercentAndUnderscore() {
        assertThat(LikePatterns.contains("a%b_c")).isEqualTo("%a\\%b\\_c%");
    }

    @Test
    void escapesBackslash() {
        assertThat(LikePatterns.contains("a\\b")).isEqualTo("%a\\\\b%");
    }

    @Test
    void trimsAndLowercases() {
        assertThat(LikePatterns.contains("  ABC  ")).isEqualTo("%abc%");
    }
}