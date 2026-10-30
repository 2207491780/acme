package com.acme.framework.ratelimit.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.DefaultRateLimitKeyBuilder;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.core.support.RateLimitKeyBuilder;
import com.acme.framework.ratelimit.aspect.RateLimitAspect;
import com.acme.framework.ratelimit.internal.RedisTokenBucket;
import com.acme.framework.ratelimit.internal.TokenBucket;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * framework-ratelimit 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 限流 key 构造器与 framework-gateway 同 bean 同源（D-001：{@code @ConditionalOnMissingBean} 两端共用，
 * 业务替换一次两端生效）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("ratelimit")
@EnableConfigurationProperties(RatelimitProperties.class)
public class RatelimitAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 限流 key 构造器（Modules.md 3.1.3、D-001；可替换，与网关同源）。 */
    @Bean
    @ConditionalOnMissingBean(RateLimitKeyBuilder.class)
    public RateLimitKeyBuilder rateLimitKeyBuilder() {
        return new DefaultRateLimitKeyBuilder();
    }

    /** Redis 令牌桶（FR-016）。 */
    @Bean
    @ConditionalOnMissingBean(TokenBucket.class)
    public TokenBucket redisTokenBucket(StringRedisTemplate redisTemplate, RatelimitProperties properties) {
        return new RedisTokenBucket(redisTemplate, properties.getRedisTimeout());
    }

    /** 资源级限流切面（FR-016~018）。 */
    @Bean
    public RateLimitAspect rateLimitAspect(TokenBucket tokenBucket, RateLimitKeyBuilder keyBuilder,
                                           KeyComposer keyComposer, RatelimitProperties properties) {
        return new RateLimitAspect(tokenBucket, keyBuilder, keyComposer, properties);
    }
}
