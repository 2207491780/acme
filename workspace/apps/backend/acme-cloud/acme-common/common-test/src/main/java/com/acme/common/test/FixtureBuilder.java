package com.acme.common.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * fixture 构造器（FR-031；DP-014 定稿）：快速构造测试对象（DTO/上下文等公共类型）。
 * 支持平铺字段与嵌套路径（{@code set("parent.child", v)}，中间对象按无参构造自动创建）。
 * 测试栈仅限测试域（BR-023、Q-012）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class FixtureBuilder<T> {

    private final Class<T> type;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public FixtureBuilder(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为空");
        }
        this.type = type;
    }

    /** 设置字段值；支持嵌套路径（{@code a.b.c}，中间对象自动创建），按登记顺序依序落值。 */
    public FixtureBuilder<T> set(String fieldName, Object value) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName 不能为空");
        }
        values.put(fieldName, value);
        return this;
    }

    /** 构造测试对象：无参构造 + 依序直填字段；字段不存在或不可访问显式失败（含完整路径定位）。 */
    public T build() {
        try {
            T instance = newInstance(type);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                applyPath(instance, entry.getKey(), entry.getValue());
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("fixture 构造失败：" + type.getName(), e);
        }
    }

    /** 沿路径逐段导航（中间对象缺失即创建），末段落值。 */
    private static void applyPath(Object root, String path, Object value) throws ReflectiveOperationException {
        Object target = root;
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length - 1; i++) {
            Field field = findField(target.getClass(), segments[i], path);
            Object next = field.get(target);
            if (next == null) {
                next = newInstance(field.getType());
                field.set(target, next);
            }
            target = next;
        }
        Field leaf = findField(target.getClass(), segments[segments.length - 1], path);
        leaf.set(target, value);
    }

    private static Field findField(Class<?> type, String name, String fullPath) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalArgumentException("字段不存在：" + fullPath);
    }

    private static <R> R newInstance(Class<R> type) throws ReflectiveOperationException {
        Constructor<R> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
