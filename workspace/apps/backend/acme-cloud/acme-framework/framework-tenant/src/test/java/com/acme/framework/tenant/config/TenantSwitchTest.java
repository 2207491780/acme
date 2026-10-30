package com.acme.framework.tenant.config;

import com.acme.framework.tenant.interceptor.IgnoreTenantAspect;
import com.acme.framework.tenant.support.CrossTenantAuditLogger;
import com.acme.framework.tenant.support.TenantIdentifierResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-030/AC-028/AC-029、BR-022）。
 */
class TenantSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.tenant.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TenantIdentifierResolver.class);
                    assertThat(context).doesNotHaveBean(CrossTenantAuditLogger.class);
                    assertThat(context).doesNotHaveBean(IgnoreTenantAspect.class);
                    assertThat(context).doesNotHaveBean(TenantProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        runner.run(context -> {
            TenantProperties properties = context.getBean(TenantProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getAudit().isEnabled()).isTrue();
            assertThat(context).hasSingleBean(TenantIdentifierResolver.class);
            assertThat(context).hasSingleBean(IgnoreTenantAspect.class);
        });
    }

    @Test
    void customConfigBinds() {
        runner.withPropertyValues("acme.tenant.audit.enabled=false")
                .run(context -> {
                    TenantProperties properties = context.getBean(TenantProperties.class);
                    assertThat(properties.getAudit().isEnabled()).isFalse();
                });
    }
}
