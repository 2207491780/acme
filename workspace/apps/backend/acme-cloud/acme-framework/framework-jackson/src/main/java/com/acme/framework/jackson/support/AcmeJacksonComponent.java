package com.acme.framework.jackson.support;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Jackson 组件标记（FR-008）：标注类随 Spring 组件扫描成为 bean 并注册进注入的 {@code JsonMapper}
 * （扫描口径=组件扫描可达，取舍 7）。可标注组件类型三类（DQ-007）：{@code JacksonModule}、
 * {@code ValueSerializer}、{@code ValueDeserializer}——后两类经 {@link #value()} 声明目标类型且必填。
 * 注解名为 {@code AcmeJacksonComponent}（WQ-001 更名，消除与 Boot 自带 {@code org.springframework.boot.jackson.JacksonComponent} 的同名混淆）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AcmeJacksonComponent {

    /** 目标类型：ValueSerializer/ValueDeserializer 时必填；JacksonModule 留空（DQ-007）。 */
    Class<?>[] value() default {};
}
