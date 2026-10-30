package com.acme.framework.jpa.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * lambda 元模型解析测试（Q-D08、AC-009）。
 */
class FieldRefTest {

    static class Demo {
        String name;
        Long amount;
        boolean active;

        String getName() {
            return name;
        }

        Long getAmount() {
            return amount;
        }

        boolean isActive() {
            return active;
        }

        String compute() {
            return name;
        }
    }

    @Test
    void resolvesGetterPropertyName() {
        assertThat(FieldRef.of(Demo::getName).name()).isEqualTo("name");
        assertThat(FieldRef.of(Demo::getAmount).name()).isEqualTo("amount");
    }

    @Test
    void resolvesBooleanIsGetter() {
        assertThat(FieldRef.of(Demo::isActive).name()).isEqualTo("active");
    }

    @Test
    void rejectsNonGetterReference() {
        assertThatThrownBy(() -> FieldRef.of(Demo::compute))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityByName() {
        assertThat(FieldRef.of(Demo::getName)).isEqualTo(FieldRef.of(Demo::getName));
    }
}
