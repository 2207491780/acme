package com.acme.framework.trace.config;

import com.acme.framework.trace.interceptor.TraceFeignRequestInterceptor;
import com.acme.framework.trace.interceptor.TraceHttpFilter;
import com.acme.framework.trace.interceptor.TraceMessageSendInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-006、AC-009/AC-010；NFR-001/NFR-005）。
 */
class TraceSwitchTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class));

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class));

    // ---------- AC-009（FR-006、BR-006） ----------

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        webRunner.withPropertyValues("acme.trace.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TraceHttpFilter.class);
                    assertThat(context).doesNotHaveBean(TraceFeignRequestInterceptor.class);
                    assertThat(context).doesNotHaveBean(TraceMessageSendInterceptor.class);
                    assertThat(context).doesNotHaveBean(TraceProperties.class);
                });
    }

    // ---------- AC-010（Q-004、NFR-005） ----------

    @Test
    void zeroConfigStartsWithDefaultsAndFullFunction() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(TraceHttpFilter.class);
            assertThat(context).hasSingleBean(TraceFeignRequestInterceptor.class);
            assertThat(context).hasSingleBean(TraceMessageSendInterceptor.class);
            TraceProperties properties = context.getBean(TraceProperties.class);
            assertThat(properties.isEnabled()).isTrue();
        });
    }

    // ---------- 类加载防护（架构设计 3.8）：非 Servlet 环境不装 HTTP 通道 ----------

    @Test
    void httpChannelAssembledOnlyOnServletWebApp() {
        nonWebRunner.run(context -> {
            assertThat(context).doesNotHaveBean(TraceHttpFilter.class);
            assertThat(context).hasSingleBean(TraceFeignRequestInterceptor.class);
            assertThat(context).hasSingleBean(TraceMessageSendInterceptor.class);
        });
    }
}
