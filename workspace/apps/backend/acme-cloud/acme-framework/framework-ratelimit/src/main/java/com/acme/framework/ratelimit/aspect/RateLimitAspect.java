package com.acme.framework.ratelimit.aspect;

import com.acme.common.context.Channel;
import com.acme.common.context.ClientContext;
import com.acme.common.context.UserContext;
import com.acme.common.core.BizException;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.core.support.RateLimitKeyBuilder;
import com.acme.framework.core.support.RateLimitKeyInput;
import com.acme.framework.core.support.RateLimitOnError;
import com.acme.framework.ratelimit.config.RatelimitProperties;
import com.acme.framework.ratelimit.internal.TokenBucket;
import com.acme.framework.ratelimit.support.RateLimit;
import com.acme.framework.ratelimit.support.RateLimitError;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * 资源级限流切面（FR-016/017/018）：key 经 {@link RateLimitKeyBuilder} 统一构造（Modules.md 3.1.3、D-001），
 * api 段=注解声明的资源标识（原样入键，架构设计 11.3）；超限抛 {@code BizException(SYS-17-0001)}（429）；
 * 判定异常按 on-error（默认 FAIL_OPEN）处置，行为可判定、不无限阻塞。
 */
@Aspect
public class RateLimitAspect {

    private final TokenBucket tokenBucket;
    private final RateLimitKeyBuilder keyBuilder;
    private final KeyComposer keyComposer;
    private final RatelimitProperties properties;

    public RateLimitAspect(TokenBucket tokenBucket, RateLimitKeyBuilder keyBuilder,
                           KeyComposer keyComposer, RatelimitProperties properties) {
        this.tokenBucket = tokenBucket;
        this.keyBuilder = keyBuilder;
        this.keyComposer = keyComposer;
        this.properties = properties;
    }

    /** 注解限流（FR-016）。 */
    @Around("@annotation(rateLimit)")
    public Object limit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        long capacity = rateLimit.capacity() > 0 ? rateLimit.capacity() : properties.getDefaultCapacity();
        double refill = rateLimit.refillPermitsPerSecond() > 0
                ? rateLimit.refillPermitsPerSecond() : properties.getDefaultRefillPermitsPerSecond();
        RateLimitOnError onError = resolveOnErrorUsed(rateLimit.onError());
        String key = keyBuilder.build(new RateLimitKeyInput(
                keyComposer.servicePrefix(),
                channel(),
                account(),
                deviceId(),
                userId(),
                rateLimit.resource()));
        boolean allowed;
        try {
            allowed = tokenBucket.tryAcquire(key, capacity, refill);
        } catch (RuntimeException ex) {
            if (onError == RateLimitOnError.FAIL_CLOSE) {
                throw new BizException(RateLimitError.RATE_LIMITED);
            }
            // FAIL_OPEN：可用性优先（取舍 #15）
            allowed = true;
        }
        if (!allowed) {
            throw new BizException(RateLimitError.RATE_LIMITED);
        }
        return joinPoint.proceed();
    }

    private static RateLimitOnError resolveOnError(RateLimit.OnError override) {
        return switch (override) {
            case FAIL_OPEN -> RateLimitOnError.FAIL_OPEN;
            case FAIL_CLOSE -> RateLimitOnError.FAIL_CLOSE;
            case DEFAULT -> null;
        };
    }

    private RateLimitOnError resolveOnErrorUsed(RateLimit.OnError override) {
        RateLimitOnError resolved = resolveOnError(override);
        return resolved == null ? properties.getOnError() : resolved;
    }

    private static Channel channel() {
        Channel channel = ClientContext.get() == null ? null : ClientContext.get().channel();
        return channel == null ? Channel.B : channel;
    }

    private static String account() {
        return UserContext.get() == null ? null : UserContext.get().userId();
    }

    private static String deviceId() {
        return ClientContext.get() == null ? null : ClientContext.get().deviceId();
    }

    private static String userId() {
        return account();
    }
}
