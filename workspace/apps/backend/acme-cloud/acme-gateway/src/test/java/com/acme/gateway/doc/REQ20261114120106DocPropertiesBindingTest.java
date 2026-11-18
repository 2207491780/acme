package com.acme.gateway.doc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GatewayDocProperties 配置绑定核对（REQ-20261114-120106、AC-1）：round1 打回单 AMB-3 钉死
 * 键面 acme.doc.enabled（boolean，默认 true，绑定 acme-gateway 的 GatewayDocProperties）。
 * 既有 DocGuardFilterTest 覆盖开关行为分支，本类只断言配置键与缺省值绑定，不改它。
 */
class REQ20261114120106DocPropertiesBindingTest {

    @EnableConfigurationProperties(GatewayDocProperties.class)
    static class BindingConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void acmeDocEnabledDefaultsToTrue() {
        runner.run(context -> assertThat(context.getBean(GatewayDocProperties.class).isEnabled()).isTrue());
    }

    @Test
    void acmeDocEnabledFalseBindsToSwitch() {
        runner.withPropertyValues("acme.doc.enabled=false")
                .run(context -> assertThat(context.getBean(GatewayDocProperties.class).isEnabled()).isFalse());
    }
}
