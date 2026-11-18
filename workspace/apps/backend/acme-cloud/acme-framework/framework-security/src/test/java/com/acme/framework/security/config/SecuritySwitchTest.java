package com.acme.framework.security.config;

import com.acme.framework.security.interceptor.DataPermissionAspect;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-026、AC-020、BR-017、7.7 密钥注入口径）。
 */
class SecuritySwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
            .withPropertyValues("acme.security.token.hmac-secret=p3-access-layer-acceptance-test-secret-0123456789");

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.security.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(InternalTokenVerifier.class);
                    assertThat(context).doesNotHaveBean(InternalTokenIssuer.class);
                    assertThat(context).doesNotHaveBean(DataPermissionAspect.class);
                    assertThat(context).doesNotHaveBean(SecurityProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        SecurityProperties properties = new SecurityProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getPublicPaths()).isEmpty();
        assertThat(properties.getToken().getIssuer()).isEqualTo("acme-gateway");
        assertThat(properties.getToken().getTtlSeconds()).isEqualTo(300);
        assertThat(properties.getToken().getClockSkewSeconds()).isEqualTo(30);
        assertThat(properties.getDataPermission().isEnabled()).isTrue();
    }

    @Test
    void issuerAssembledFromSameTokenConfig() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InternalTokenIssuer.class);
            assertThat(context).hasSingleBean(InternalTokenVerifier.class);
        });
    }

    @Test
    void blankSecretFailsFast() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed());
    }
}
