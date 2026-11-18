package com.acme.common.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FixtureBuilder 契约级语义测试（AC-005、架构设计 6.2；DP-014 定稿）：
 * 平铺字段直填、嵌套路径导航（中间对象自动创建）、失败显式定位。
 */
class FixtureBuilderTest {

    static class Demo {
        String name;
        Integer count;
    }

    static class Wrapper {
        Demo inner;
        String label;
    }

    @Test
    void constructAndBuild() {
        Demo demo = new FixtureBuilder<>(Demo.class)
                .set("name", "demo")
                .set("count", 1)
                .build();
        assertEquals("demo", demo.name);
        assertEquals(1, demo.count.intValue());
    }

    /** 嵌套路径：中间对象缺失时自动创建并逐段落值（DP-014 定稿）。 */
    @Test
    void nestedPathCreatesIntermediateObjects() {
        Wrapper wrapper = new FixtureBuilder<>(Wrapper.class)
                .set("label", "w")
                .set("inner.name", "nested")
                .set("inner.count", 2)
                .build();
        assertEquals("w", wrapper.label);
        assertNotNull(wrapper.inner);
        assertEquals("nested", wrapper.inner.name);
        assertEquals(2, wrapper.inner.count.intValue());
    }

    /** 中间对象已存在（经 set 预置）时原样导航，不覆盖。 */
    @Test
    void nestedPathReusesExistingIntermediate() {
        Demo existing = new Demo();
        Wrapper built = new FixtureBuilder<>(Wrapper.class)
                .set("inner", existing)
                .set("inner.name", "kept")
                .build();
        assertSame(existing, built.inner);
        assertEquals("kept", existing.name);
    }

    @Test
    void missingFieldFailsWithPathDetail() {
        FixtureBuilder<Demo> builder = new FixtureBuilder<>(Demo.class).set("nope", 1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals("字段不存在：nope", e.getMessage());
    }
}
