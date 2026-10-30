package com.acme.framework.security.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限拦截标记（FR-017、Q-D09：注解 + AOP + 扩展点）：标注受保护的接口/服务方法；
 * 不含业务数据权限规则（8.1.2、Q-004），规则由 {@link DataPermissionEvaluator} 业务实现提供。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataPermission {

    /** 数据权限键（业务规则键，框架不解释语义，8.1.2）。 */
    String value();
}
