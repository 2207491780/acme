package com.acme.framework.lock.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解式互斥入口（FR-010/AC-010）：方法进入自动加锁、退出自动释放（含异常路径）。
 * 锁 key={@code {服务}:l:{key}}（架构设计 7.4，经 core KeyComposer）。
 *
 * <p>{@link #key()} 保持静态字面量（防 SpEL 注入面）；动态键场景改用
 * {@link DistributedLocks#execute} 编程式入口。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /** 锁名（拼入规范 key；保持静态字面量，动态键走 {@code DistributedLocks.execute}）。 */
    String key();

    /** 失败策略（覆盖配置默认；FR-012）。 */
    LockFailurePolicy policy() default LockFailurePolicy.FAIL_FAST;

    /** WAIT 策略等待上限毫秒（0=取配置默认 acme.lock.wait-millis）。 */
    long waitMillis() default 0;

    /** 固定租期秒（0=看门狗模式，FR-011）。 */
    long leaseSeconds() default 0;
}
