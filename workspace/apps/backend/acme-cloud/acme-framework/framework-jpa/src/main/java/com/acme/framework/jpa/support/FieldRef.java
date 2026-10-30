package com.acme.framework.jpa.support;

import java.beans.Introspector;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

/**
 * 实体属性引用（Q-D08：lambda 方法引用元模型，类型安全、零 QueryDSL API 暴露，BR-021）。
 * 使用方经 {@code FieldRef.of(SampleItem::getName)} 声明属性，不触碰任何 {@code com.querydsl.*} 类型（AC-009）。
 * 属性解析按 lambda 类缓存（热路径零重复反射）；相等性含声明类（Q-D08：跨实体同名属性不相等）。
 */
public final class FieldRef<T> {

    /** lambda 调用点类 → 解析结果（声明类 + 属性名）。 */
    private static final java.util.Map<Class<?>, Resolved> RESOLVED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final String owner;
    private final String name;

    private FieldRef(String owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    /** 由 getter 方法引用解析属性名（6.5）；同一调用点 lambda 类仅首次反射，其后走缓存。 */
    public static <T, V> FieldRef<T> of(SerializableFunction<T, V> getter) {
        Class<?> lambdaClass = getter.getClass();
        Resolved resolved = RESOLVED.get(lambdaClass);
        if (resolved == null) {
            resolved = resolve(getter);
            RESOLVED.put(lambdaClass, resolved);
        }
        return new FieldRef<>(resolved.owner(), resolved.name());
    }

    /** 属性名（6.5）。 */
    public String name() {
        return name;
    }

    /** 声明类（Q-D08 相等性要素）。 */
    public String owner() {
        return owner;
    }

    private record Resolved(String owner, String name) {
    }

    private static Resolved resolve(SerializableFunction<?, ?> getter) {
        try {
            Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            SerializedLambda lambda = (SerializedLambda) writeReplace.invoke(getter);
            return new Resolved(lambda.getImplClass(), propertyName(lambda.getImplMethodName()));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("属性解析失败（Q-D08）", ex);
        }
    }

    private static String propertyName(String method) {
        if (method.startsWith("get") && method.length() > 3) {
            return Introspector.decapitalize(method.substring(3));
        }
        if (method.startsWith("is") && method.length() > 2) {
            return Introspector.decapitalize(method.substring(2));
        }
        throw new IllegalArgumentException("方法引用不是 getter 形态（Q-D08）：" + method);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FieldRef<?> ref && owner.equals(ref.owner) && name.equals(ref.name);
    }

    @Override
    public int hashCode() {
        return owner.hashCode() * 31 + name.hashCode();
    }

    @Override
    public String toString() {
        return "FieldRef(" + owner + "#" + name + ")";
    }
}
