package com.acme.framework.jpa.support;

import java.io.Serializable;
import java.util.function.Function;

/**
 * 可序列化函数引用（Q-D08 lambda 元模型载体）：方法引用经 SerializedLambda 解析属性名（FieldRef）。
 */
@FunctionalInterface
public interface SerializableFunction<T, R> extends Function<T, R>, Serializable {
}
