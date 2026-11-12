package com.acme.framework.feign.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等声明（FR-019、取舍 #9）：覆盖 HTTP 方法默认幂等判定（默认 GET/HEAD/PUT/DELETE/OPTIONS 幂等）；
 * 仅幂等调用参与指数退避重试（10.3.3）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FeignIdempotent {

    /** true=强制幂等（可重试）；false=强制非幂等（不重试）。 */
    boolean value() default true;
}
