package com.acme.framework.gateway.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.DefaultRateLimitKeyBuilder;
import com.acme.framework.core.support.RateLimitKeyBuilder;
import com.acme.framework.gateway.internal.DefaultUserTokenVerifier;
import com.acme.framework.gateway.internal.NeverRevokedTokenChecker;
import com.acme.framework.gateway.internal.RateLimiters;
import com.acme.framework.gateway.interceptor.AuthFilter;
import com.acme.framework.gateway.interceptor.GatewayErrorWebExceptionHandler;
import com.acme.framework.gateway.interceptor.InjectIdentityFilter;
import com.acme.framework.gateway.interceptor.RateLimitFilter;
import com.acme.framework.gateway.interceptor.RequestLogFilter;
import com.acme.framework.gateway.interceptor.RevocationCheckFilter;
import com.acme.framework.gateway.interceptor.StripSpoofedHeadersFilter;
import com.acme.framework.gateway.interceptor.TraceWebFilter;
import com.acme.framework.gateway.support.UserTokenRevocationChecker;
import com.acme.framework.gateway.support.UserTokenVerifier;
import com.acme.framework.security.config.SecurityProperties;
import com.acme.framework.security.support.InternalTokenIssuer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * framework-gateway 总装（FR-026 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * WebFlux 形态（@ConditionalOnWebApplication(REACTIVE)，8.0）；依赖树禁任何 Servlet（4.2、BR-004，enforcer 强制）；
 * 灰度复用 SCG 原生路由谓词（8.6，取舍 #10），本模块仅提供标记头常量（{@link GatewayProperties.Gray}）；
 * 内部令牌签发经 framework-security {@link InternalTokenIssuer} 接缝（Q-D001、21.1），配置收敛 acme.security.token.*；
 * 用户令牌验签密钥域独立 acme.gateway.user-token.*（11.6.2）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("gateway")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties({GatewayProperties.class, SecurityProperties.class})
public class GatewayAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 用户令牌验签（FR-019；密钥域 acme.gateway.user-token.* 与内部令牌隔离，11.6.2；可注册 UserTokenVerifier 覆盖）。 */
    @Bean
    @ConditionalOnMissingBean(UserTokenVerifier.class)
    public UserTokenVerifier userTokenVerifier(GatewayProperties properties) {
        return new DefaultUserTokenVerifier(properties.getUserToken());
    }

    /** 用户令牌撤销检查（Q-D02；缺省永不撤销，行为与未引入一致；应用可注册实现覆盖，如 Redis 直查）。 */
    @Bean
    @ConditionalOnMissingBean(UserTokenRevocationChecker.class)
    public UserTokenRevocationChecker userTokenRevocationChecker() {
        return new NeverRevokedTokenChecker();
    }

    /** 撤销检查过滤器（Q-D02：验签后逐请求判定；异常兜底随 acme.gateway.revocation.*）。 */
    @Bean
    public RevocationCheckFilter revocationCheckFilter(UserTokenRevocationChecker checker,
                                                       GatewayProperties properties) {
        return new RevocationCheckFilter(checker, properties.getRevocation());
    }

    /** 限流 key 构造器（BR-012：统一构造，可替换）。 */
    @Bean
    @ConditionalOnMissingBean(RateLimitKeyBuilder.class)
    public RateLimitKeyBuilder rateLimitKeyBuilder() {
        return new DefaultRateLimitKeyBuilder();
    }

    /** 限流执行器（FR-022、Q-D07）。 */
    @Bean
    public RateLimiters rateLimiters(GatewayProperties properties) {
        return new RateLimiters(properties.getRatelimit());
    }

    /** trace 透传（8.0，取舍 #4）。 */
    @Bean
    public TraceWebFilter traceWebFilter() {
        return new TraceWebFilter();
    }

    /** 过滤链：剥离伪造头 → 鉴权 → 注入 → 限流 → 请求日志（8.1）。 */
    @Bean
    public StripSpoofedHeadersFilter stripSpoofedHeadersFilter() {
        return new StripSpoofedHeadersFilter();
    }

    @Bean
    public AuthFilter authFilter(UserTokenVerifier verifier, GatewayProperties properties) {
        return new AuthFilter(verifier, properties.getAuth());
    }

    @Bean
    public InjectIdentityFilter injectIdentityFilter(ObjectProvider<InternalTokenIssuer> issuer) {
        return new InjectIdentityFilter(issuer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "acme.gateway.ratelimit", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public RateLimitFilter rateLimitFilter(RateLimiters limiters,
                                           ObjectProvider<RateLimitKeyBuilder> keyBuilder,
                                           GatewayProperties properties) {
        return new RateLimitFilter(limiters,
                keyBuilder.getIfAvailable(DefaultRateLimitKeyBuilder::new),
                properties.getRatelimit());
    }

    @Bean
    @ConditionalOnProperty(prefix = "acme.gateway.request-log", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public RequestLogFilter requestLogFilter(Environment environment) {
        return new RequestLogFilter(environment.getProperty("spring.application.name", "application"));
    }

    /** 网关自身错误统一封装（FR-025、8.8）。 */
    @Bean
    public GatewayErrorWebExceptionHandler gatewayErrorWebExceptionHandler() {
        return new GatewayErrorWebExceptionHandler();
    }
}
