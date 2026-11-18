package com.acme.framework.feign.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.feign.interceptor.ContextHeaderRequestInterceptor;
import com.acme.framework.feign.interceptor.FeignErrorDecoder;
import com.acme.framework.feign.interceptor.InternalTokenRequestInterceptor;
import com.acme.framework.feign.internal.FeignInvocationCapability;
import com.acme.framework.feign.internal.ResilientClientCapability;
import com.acme.framework.feign.support.FeignFallback;
import com.acme.framework.security.support.InternalTokenIssuer;
import feign.Capability;
import feign.Client;
import feign.Request;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * framework-feign 总装（FR-030 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * trace 透传由 P2 TraceFeignRequestInterceptor 自动拾取（FR-024、BR-024，不重复实现）；
 * 内部令牌签发经 framework-security 接缝（Q-D001）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("feign")
@ConditionalOnClass(Client.class)
@EnableConfigurationProperties(FeignProperties.class)
public class FeignAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 显式超时（FR-018、10.4.2：connect/read 显式声明）。 */
    @Bean
    @ConditionalOnMissingBean(Request.Options.class)
    public Request.Options feignRequestOptions(FeignProperties properties) {
        return new Request.Options(
                Math.toIntExact(properties.getConnectTimeoutMillis()),
                Math.toIntExact(properties.getReadTimeoutMillis()));
    }

    /** feign 内建重试关闭（避免双重重试，9.2）。 */
    @Bean
    @ConditionalOnMissingBean(Retryer.class)
    public Retryer feignNeverRetry() {
        return Retryer.NEVER_RETRY;
    }

    /** 熔断注册表（FR-020、Q-D004 裸 resilience4j）。 */
    @Bean
    @ConditionalOnMissingBean(CircuitBreakerRegistry.class)
    public CircuitBreakerRegistry feignCircuitBreakerRegistry(FeignProperties properties) {
        FeignProperties.Circuit circuit = properties.getCircuit();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(circuit.getFailureRateThreshold())
                .slidingWindowSize(circuit.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(circuit.getWaitDurationInOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(circuit.getPermittedCallsInHalfOpen())
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /** 错误解码为 BizException（FR-021）。 */
    @Bean
    @ConditionalOnMissingBean(ErrorDecoder.class)
    public ErrorDecoder feignErrorDecoder() {
        return new FeignErrorDecoder();
    }

    /** 内部令牌注入（FR-022/AC-022；签发器延迟解析，规避自动装配条件时序）。 */
    @Bean
    public InternalTokenRequestInterceptor internalTokenRequestInterceptor(
            ObjectProvider<InternalTokenIssuer> issuers, FeignProperties properties) {
        return new InternalTokenRequestInterceptor(issuers::getObject, properties);
    }

    /** 上下文头注入（FR-023/AC-023）。 */
    @Bean
    public ContextHeaderRequestInterceptor contextHeaderRequestInterceptor() {
        return new ContextHeaderRequestInterceptor();
    }

    /** Client 装饰（FR-018/019/020，Q-D004）。 */
    @Bean
    public Capability resilientClientCapability(FeignProperties properties,
                                                CircuitBreakerRegistry circuitBreakerRegistry) {
        return new ResilientClientCapability(properties, circuitBreakerRegistry);
    }

    /** 调用层装饰（FR-019 幂等打标、FR-020 降级 SPI）。 */
    @Bean
    public Capability feignInvocationCapability(ObjectProvider<FeignFallback> fallbackProvider) {
        return new FeignInvocationCapability(fallbackProvider);
    }
}
