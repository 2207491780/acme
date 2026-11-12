package com.acme.framework.storage.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对象键路径约定测试（FR-011、架构设计 9.1）。
 */
class ObjectKeyTest {

    @Test
    void pathFollowsNamespaceDateName() {
        ObjectKey key = ObjectKey.of("reports", "a.pdf", LocalDate.of(2026, 10, 21));
        assertThat(key.path()).isEqualTo("reports/2026/10/21/a.pdf");
    }

    @Test
    void rejectsBlankParts() {
        assertThatThrownBy(() -> ObjectKey.of(" ", "a.pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectKey.of("reports", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityByPath() {
        assertThat(ObjectKey.of("n", "x", LocalDate.of(2026, 1, 1)))
                .isEqualTo(ObjectKey.of("n", "x", LocalDate.of(2026, 1, 1)));
    }
}
