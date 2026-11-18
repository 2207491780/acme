package com.acme.common.serialization;

import com.acme.common.serialization.internal.DesensitizeSerializer;
import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import tools.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 脱敏标记（FR-025）。定义于 common-serialization（BR-002 限制 serialization 不得引用 core 类型，
 * 注解不能放 core；DTO 字段经 *-api 依赖标注，Backend.md 5.2）。
 *
 * <p>策略集与掩码规则见 {@link DesensitizeStrategy}（DP-010 定稿）。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = DesensitizeSerializer.class)
public @interface Desensitize {

    /** 脱敏策略（FR-025）。 */
    DesensitizeStrategy strategy();
}
