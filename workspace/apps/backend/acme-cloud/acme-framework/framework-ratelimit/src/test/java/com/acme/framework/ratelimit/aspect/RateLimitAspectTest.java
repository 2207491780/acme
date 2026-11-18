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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资源级限流切面测试（FR-016~018、AC-017/018、Modules.md 3.1.3 key 同源）。
 */
class RateLimitAspectTest {

    private final List<RateLimitKeyInput> captured = new ArrayList<>();
    private final AtomicBoolean allow = new AtomicBoolean(true);
    private final AtomicBoolean bucketFails = new AtomicBoolean(false);

    private final TokenBucket bucket = (key, capacity, refill) -> {
        if (bucketFails.get()) {
            throw new IllegalStateException("redis down");
        }
        return allow.get();
    };

    private final RateLimitKeyBuilder builder = new RateLimitKeyBuilder() {
        @Override
        public String build(RateLimitKeyInput input) {
            captured.add(input);
            return "key";
        }

        @Override
        public String buildFallback(com.acme.framework.core.support.RateLimitFallbackInput input) {
            return "fallback";
        }
    };

    @AfterEach
    void cleanup() {
        UserContext.clear();
        ClientContext.clear();
    }

    private Sample proxy(RatelimitProperties properties) {
        RateLimitAspect aspect = new RateLimitAspect(bucket, builder, new KeyComposer("order-service"), properties);
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleImpl());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    void grantsWithinQuotaAndBuildsSharedKey() {
        UserContext.bind(new UserContext("acct-1", Set.of()));
        ClientContext.bind(new ClientContext(Channel.B, "dev-1", "1.0"));
        Sample sample = proxy(new RatelimitProperties());
        assertThat(sample.write()).isEqualTo("ok");
        assertThat(captured).hasSize(1);
        RateLimitKeyInput input = captured.getFirst();
        assertThat(input.servicePrefix()).isEqualTo("order-service");
        assertThat(input.channel()).isEqualTo(Channel.B);
        assertThat(input.account()).isEqualTo("acct-1");
        assertThat(input.api()).isEqualTo("order.write");
    }

    @Test
    void overQuotaThrowsRateLimited() {
        allow.set(false);
        Sample sample = proxy(new RatelimitProperties());
        assertThatThrownBy(sample::write)
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RateLimitError.RATE_LIMITED));
    }

    @Test
    void bucketFailureFollowsGlobalOnError() {
        bucketFails.set(true);
        RatelimitProperties failOpen = new RatelimitProperties();
        assertThat(proxy(failOpen).write()).isEqualTo("ok");

        RatelimitProperties failClose = new RatelimitProperties();
        failClose.setOnError(RateLimitOnError.FAIL_CLOSE);
        assertThatThrownBy(() -> proxy(failClose).write())
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RateLimitError.RATE_LIMITED));
    }

    @Test
    void annotationOverridesGlobalOnError() {
        bucketFails.set(true);
        RatelimitProperties failClose = new RatelimitProperties();
        failClose.setOnError(RateLimitOnError.FAIL_CLOSE);
        assertThat(proxy(failClose).overrideFailOpen()).isEqualTo("ok");
    }

    interface Sample {
        String write();

        String overrideFailOpen();
    }

    static class SampleImpl implements Sample {
        @Override
        @RateLimit(resource = "order.write")
        public String write() {
            return "ok";
        }

        @Override
        @RateLimit(resource = "order.write", onError = RateLimit.OnError.FAIL_OPEN)
        public String overrideFailOpen() {
            return "ok";
        }
    }
}
