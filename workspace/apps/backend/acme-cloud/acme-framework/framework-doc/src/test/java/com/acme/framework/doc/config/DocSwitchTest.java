package com.acme.framework.doc.config;

import com.acme.framework.doc.internal.DocEnvironmentPostProcessor;
import com.acme.framework.doc.internal.EndGroups;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关、配置缺省与可关闭性测试（FR-014/015/AC-014/015、NFR-004、BR-003/004、Q-008）。
 */
class DocSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DocAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.doc.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OpenAPI.class);
                    assertThat(context).doesNotHaveBean(GroupedOpenApi.class);
                    assertThat(context).doesNotHaveBean(DocProperties.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaultsAndEndGroups() {
        runner.run(context -> {
            DocProperties properties = context.getBean(DocProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getTitle()).isEqualTo("Acme API");
            assertThat(properties.getVersion()).isEqualTo("1.0.0");
            assertThat(properties.getApiDocsPath()).isEqualTo("/v3/api-docs");
            assertThat(properties.getSwaggerUiPath()).isEqualTo("/swagger-ui");
            assertThat(properties.isGroupByEnd()).isTrue();
            assertThat(context.getBeansOfType(GroupedOpenApi.class)).hasSize(2);
            assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("Acme API");
        });
    }

    @Test
    void groupByEndCanBeDisabled() {
        runner.withPropertyValues("acme.doc.group-by-end=false")
                .run(context -> assertThat(context.getBeansOfType(GroupedOpenApi.class)).isEmpty());
    }

    @Test
    void disableBridgesSpringdocEndpointsOff() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("acme.doc.enabled", "false");
        new DocEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
    }

    @Test
    void pathsBridgeToSpringdocWithoutOverridingUserConfig() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("acme.doc.api-docs-path", "/custom-docs");
        new DocEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("springdoc.api-docs.path")).isEqualTo("/custom-docs");

        MockEnvironment userSet = new MockEnvironment();
        userSet.setProperty("acme.doc.api-docs-path", "/custom-docs");
        userSet.setProperty("springdoc.api-docs.path", "/user-path");
        new DocEnvironmentPostProcessor().postProcessEnvironment(userSet, null);
        assertThat(userSet.getProperty("springdoc.api-docs.path")).isEqualTo("/user-path");
    }

    @Test
    void endGroupsMatchAdminAppPrefixes() {
        assertThat(EndGroups.admin().getPathsToMatch()).containsExactly(EndGroups.ADMIN_PREFIX);
        assertThat(EndGroups.app().getPathsToMatch()).containsExactly(EndGroups.APP_PREFIX);
    }
}
