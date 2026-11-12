package com.acme.framework.ratelimit.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源级限流声明（FR-016、8.2.1）：资源定义归使用方（本注解只提供机制，业务语义归业务侧）；
 * 限流 key 由 framework-core 共享构造器统一构造（Modules.md 3.1.3、D-001），业务代码不得自造限流键。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 资源标识（原样入键，架构设计 11.3）。 */
    String resource();

    /** 桶容量；{@code -1} 取 {@code acme.ratelimit.default-capacity}。 */
    long capacity() default -1;

    /** 每秒补充许可数；{@code -1} 取 {@code acme.ratelimit.default-refill-permits-per-second}。 */
    double refillPermitsPerSecond() default -1;

    /** 判定异常兜底覆盖（默认沿全局 {@code acme.ratelimit.on-error}）。 */
    OnError onError() default OnError.DEFAULT;

    /** 判定异常兜底覆盖取值。 */
    enum OnError {
        /** 沿全局配置。 */
        DEFAULT,
        /** 判定异常放行。 */
        FAIL_OPEN,
        /** 判定异常拒绝。 */
        FAIL_CLOSE
    }
}
