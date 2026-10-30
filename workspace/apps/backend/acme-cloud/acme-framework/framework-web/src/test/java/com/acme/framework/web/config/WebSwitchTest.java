package com.acme.framework.web.config;

import com.acme.framework.web.interceptor.AccessLogFilter;
import com.acme.framework.web.interceptor.ClientContextExtractFilter;
import com.acme.framework.web.interceptor.GlobalExceptionTranslator;
import com.acme.framework.web.interceptor.StandardResponseAdvice;
import com.acme.framework.web.support.ClientContextParser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-026、AC-020、BR-017）；过滤器经 Web runner 断言开关。
 */
class WebSwitchTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebAutoConfiguration.class));

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        webRunner.withPropertyValues("acme.web.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(StandardResponseAdvice.class);
                    assertThat(context).doesNotHaveBean(GlobalExceptionTranslator.class);
                    assertThat(context).doesNotHaveBean(ClientContextParser.class);
                    assertThat(context).doesNotHaveBean(WebProperties.class);
                    assertThat(context).doesNotHaveBean(AccessLogFilter.class);
                    assertThat(context).doesNotHaveBean(ClientContextExtractFilter.class);
                });
    }

    @Test
    void zeroConfigStartsWithDefaults() {
        WebProperties properties = new WebProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAccessLog().isEnabled()).isTrue();
        assertThat(properties.getClientContext().getInvalidPolicy()).isEqualTo(ClientContextInvalidPolicy.IGNORE);
    }

    @Test
    void coreBeansAssembleWhenEnabled() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(StandardResponseAdvice.class);
            assertThat(context).hasSingleBean(GlobalExceptionTranslator.class);
            assertThat(context).hasSingleBean(ClientContextParser.class);
        });
    }

    /** 过滤器 bean 开关（FR-006/007、对照 trace 模块 TraceSwitchTest 写法）。 */
    @Test
    void filterBeansAssembleOnServletWebApp() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(AccessLogFilter.class);
            assertThat(context).hasSingleBean(ClientContextExtractFilter.class);
        });
    }

    @Test
    void accessLogFilterSwitchedOffByConfig() {
        webRunner.withPropertyValues("acme.web.access-log.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AccessLogFilter.class);
                    assertThat(context).hasSingleBean(ClientContextExtractFilter.class);
                });
    }

    /** 类加载防护：非 Servlet 环境不装过滤器。 */
    @Test
    void filterBeansAssembledOnlyOnServletWebApp() {
        nonWebRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AccessLogFilter.class);
            assertThat(context).doesNotHaveBean(ClientContextExtractFilter.class);
            assertThat(context).hasSingleBean(StandardResponseAdvice.class);
        });
    }
}
