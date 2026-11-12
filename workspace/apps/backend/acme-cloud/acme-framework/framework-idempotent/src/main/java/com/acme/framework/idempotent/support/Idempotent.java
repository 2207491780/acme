package com.acme.framework.idempotent.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * HTTP token 入口幂等声明（FR-001、Backend.md 10.3.1）：同 token 重复请求仅执行一次并回放缓存结果；
 * 幂等键业务语义归业务侧（Modules.md 5），本注解只提供机制。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 业务键 SpEL（缺省取幂等 token 头 {@code acme.idempotent.header-name}）。 */
    String key() default "";

    /** token 缺失处置（默认沿 {@code acme.idempotent.missing-token-policy}）。 */
    MissingTokenPolicy missingTokenPolicy() default MissingTokenPolicy.REJECT;

    /** 在途处置（默认沿 {@code acme.idempotent.in-flight-policy}，Q-D002）。 */
    InFlightPolicy inFlightPolicy() default InFlightPolicy.WAIT;
}
