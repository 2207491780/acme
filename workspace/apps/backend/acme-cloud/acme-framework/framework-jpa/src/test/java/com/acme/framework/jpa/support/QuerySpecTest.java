package com.acme.framework.jpa.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查询模型测试（FR-011、Q-D08）。
 */
class QuerySpecTest {

    static class Demo {
        String name;

        String getName() {
            return name;
        }
    }

    @Test
    void buildsConditionsOrdersAndPage() {
        QuerySpec<Demo> spec = QuerySpec.of(Demo.class)
                .where(FieldRef.of(Demo::getName), Op.STARTS_WITH, "a")
                .orderBy(FieldRef.of(Demo::getName), SortDir.DESC)
                .page(2, 10);
        assertThat(spec.entity()).isEqualTo(Demo.class);
        assertThat(spec.conditions()).hasSize(1);
        assertThat(spec.conditions().get(0).field()).isEqualTo("name");
        assertThat(spec.conditions().get(0).op()).isEqualTo(Op.STARTS_WITH);
        assertThat(spec.orders()).hasSize(1);
        assertThat(spec.page()).isEqualTo(2);
        assertThat(spec.size()).isEqualTo(10);
    }

    @Test
    void rejectsInvalidPage() {
        assertThatThrownBy(() -> QuerySpec.of(Demo.class).page(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullField() {
        assertThatThrownBy(() -> QuerySpec.of(Demo.class).where(null, Op.EQ, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullEntity() {
        assertThatThrownBy(() -> QuerySpec.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
