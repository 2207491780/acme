package com.acme.framework.jpa.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 绑定参数脱敏测试（FR-013、14.1.1、BR-020）。
 */
class SqlMaskingTest {

    @Test
    void masksSensitiveColumnInInsert() {
        String sql = "insert into t_user (name, password) values (?, ?)";
        List<String> rendered = SqlMasking.render(sql, List.of("alice", "p@ss"));
        assertThat(rendered).containsExactly("alice", "******");
    }

    @Test
    void masksSensitiveColumnInUpdate() {
        String sql = "update t_user set name = ?, phone = ? where id = ?";
        List<String> rendered = SqlMasking.render(sql, List.of("alice", "13800000000", 1L));
        assertThat(rendered.get(1)).isEqualTo("******");
        assertThat(rendered.get(0)).isEqualTo("alice");
    }

    @Test
    void keepsPlainParameters() {
        String sql = "select * from t_item where name = ? and amount = ?";
        List<String> rendered = SqlMasking.render(sql, List.of("alpha", 3));
        assertThat(rendered).containsExactly("alpha", "3");
    }

    @Test
    void conservativelyMasksWhenSqlContainsSensitiveWord() {
        String sql = "select * from t_item where token like ?";
        assertThat(SqlMasking.render(sql, List.of("abc"))).containsExactly("******");
    }

    @Test
    void rendersNullValues() {
        String sql = "insert into t_item (name, seq) values (?, ?)";
        assertThat(SqlMasking.render(sql, java.util.Arrays.asList("a", null))).containsExactly("a", "null");
    }
}
