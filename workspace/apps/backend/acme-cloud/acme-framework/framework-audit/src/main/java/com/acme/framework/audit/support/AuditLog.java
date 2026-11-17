package com.acme.framework.audit.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计埋点声明（FR-008、8.2.1：framework 不硬编码审计清单，B 全量/C 关键行为差异由业务声明覆盖）；
 * 审计业务语义归业务侧（BR-005），本注解只提供机制。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 动作标识（业务语义，如 {@code order.create}）。 */
    String action();

    /** 目标类型（SpEL，可取方法参数，如 {@code #p0.type}）。 */
    String targetType() default "";

    /** 目标标识（SpEL，可取方法参数，如 {@code #p0.id}）。 */
    String targetId() default "";

    /** 内容参数白名单（参数名或 SpEL #pN，未声明不记录内容）。 */
    String[] contentParams() default {};

    /**
     * 旧值快照 SpEL（REQ-20261117-061017 确认③，默认空）：proceed 前求值（支持 {@code @beanName}
     * 引用，如 {@code @userUseCase.get(#userId)}），结果 JSON/toString 截断 512 后以
     * {@code before=<摘要>} 前缀并入 content；表达式失败静默跳过（不阻断业务）。
     */
    String beforeQuery() default "";

    /** 敏感字段脱敏（FR-010、BR-013）。 */
    boolean mask() default true;
}
