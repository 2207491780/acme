package com.acme.framework.gateway.config;

import com.acme.framework.gateway.interceptor.AuthFilter;
import com.acme.framework.gateway.interceptor.InjectIdentityFilter;
import com.acme.framework.core.support.RateLimitKeyBuilder;
import com.acme.framework.core.support.RateLimitOnError;
import com.acme.framework.gateway.support.UserTokenVerifier;
import com.acme.framework.security.support.InternalTokenIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-026、AC-020、BR-017、7.7 密钥注入口径；Q-D001 后密钥收敛 acme.security.token.*）。
 */
class GatewaySwitchTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class))
            // 用户令牌密钥域独立（11.6.2）：acme.gateway.user-token.*；内部令牌配置不供验签
            .withPropertyValues("acme.gateway.user-token.hmac-secret=p3-access-layer-acceptance-test-secret-0123456789")
            // Q-D001：签发 bean 归 framework-security 装配，本模块测试以桩件供给
            .withBean(InternalTokenIssuer.class, () -> subject -> "stub-token");

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.gateway.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(UserTokenVerifier.class);
                    assertThat(context).doesNotHaveBean(InjectIdentityFilter.class);
                    assertThat(context).doesNotHaveBean(AuthFilter.class);
                    assertThat(context).doesNotHaveBean(GatewayProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        GatewayProperties properties = new GatewayProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAuth().getTokenHeader()).isEqualTo("Authorization");
        assertThat(properties.getAuth().getB().isEnabled()).isTrue();
        assertThat(properties.getAuth().getC().isEnabled()).isTrue();
        assertThat(properties.getRatelimit().isEnabled()).isTrue();
        assertThat(properties.getRatelimit().getOnError()).isEqualTo(RateLimitOnError.FAIL_OPEN);
        assertThat(properties.getRatelimit().getB().getQuotaPerMinute()).isEqualTo(120);
        assertThat(properties.getRatelimit().getC().getQuotaPerMinute()).isEqualTo(240);
        // WQ-005：兜底限流默认值
        assertThat(properties.getRatelimit().getFallback().isEnabled()).isTrue();
        assertThat(properties.getRatelimit().getFallback().getQuotaPerMinute()).isEqualTo(60);
        assertThat(properties.getGray().getHeader()).isEqualTo("X-Acme-Gray");
        assertThat(properties.getRequestLog().isEnabled()).isTrue();
    }

    @Test
    void blankSecretFailsFast() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class))
                .withBean(InternalTokenIssuer.class, () -> subject -> "stub-token")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void keyBuilderReplaceable() {
        RateLimitKeyBuilder custom = new RateLimitKeyBuilder() {
            @Override
            public String build(com.acme.framework.core.support.RateLimitKeyInput input) {
                return "custom";
            }

            @Override
            public String buildFallback(com.acme.framework.core.support.RateLimitFallbackInput input) {
                return "custom-fallback";
            }
        };
        runner.withBean(RateLimitKeyBuilder.class, () -> custom)
                .run(context -> assertThat(context.getBean(RateLimitKeyBuilder.class).build(null)).isEqualTo("custom"));
    }
}
