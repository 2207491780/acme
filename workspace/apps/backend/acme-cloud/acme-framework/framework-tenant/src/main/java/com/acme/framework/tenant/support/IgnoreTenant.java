package com.acme.framework.tenant.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 忽略租户过滤声明（FR-003/AC-003、Backend.md 12.4.2 显式声明）：标注访问点跳过租户过滤，
 * 仅限平台管理场景；每次旁路访问产生跨租户审计（FR-004），审计失败 fail-closed 阻断访问。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IgnoreTenant {

    /** 访问点描述（入审计记录；默认取类#方法）。 */
    String value() default "";
}
