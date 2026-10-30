package com.acme.framework.jpa.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 软删除实体级开关（FR-009，6.3）：标注后删除=置删除标记（默认查询过滤随 AuditBase {@code @SQLRestriction}），
 * 重复删除幂等（10.3.1）；改写适配收敛 internal（6.3 适配点核验于开发时，见实现说明）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SoftDelete {
}
