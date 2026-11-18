package com.acme.framework.trace.internal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志文件输出缺省装配测试（REQ-20261117-061017 确认⑤，AC-9 配置断言级）：
 * 缺省注入 logging.file.name + 滚动策略键、用户显式配置不覆盖、开关关不注入。
 */
class TraceFileLoggingTest {

    private final TraceLoggingPatternEnvironmentPostProcessor processor =
            new TraceLoggingPatternEnvironmentPostProcessor();

    /** 缺省注入文件键：logs/{app}.log + 按天/大小滚动 + 50MB/7 天/2GB（确认⑤）。 */
    @Test
    void injectsDefaultFileKeysWhenUnset() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-svc");
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("logging.file.name")).isEqualTo("logs/my-svc.log");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.file-name-pattern"))
                .isEqualTo("logs/my-svc.%d{yyyy-MM-dd}.%i.log");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.max-file-size")).isEqualTo("50MB");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.max-history")).isEqualTo("7");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.total-size-cap")).isEqualTo("2GB");
    }

    /** 应用名缺省为 application（AccessLogFilter 回退先例）。 */
    @Test
    void defaultsToApplicationNameWhenAppMissing() {
        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("logging.file.name")).isEqualTo("logs/application.log");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.file-name-pattern"))
                .isEqualTo("logs/application.%d{yyyy-MM-dd}.%i.log");
    }

    /** 用户已显式设置的键逐项不覆盖；未设的键照常注入（确认⑤同 pattern 键逐项守卫口径）。 */
    @Test
    void doesNotOverrideExplicitlySetFileKeys() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-svc");
        environment.setProperty("logging.file.name", "/var/log/custom.log");
        environment.setProperty("logging.logback.rollingpolicy.file-name-pattern", "custom/pattern.log");
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("logging.file.name")).isEqualTo("/var/log/custom.log");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.file-name-pattern"))
                .isEqualTo("custom/pattern.log");
        // 用户未设的滚动键逐项照常注入
        assertThat(environment.getProperty("logging.logback.rollingpolicy.max-file-size")).isEqualTo("50MB");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.max-history")).isEqualTo("7");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.total-size-cap")).isEqualTo("2GB");
    }

    /** 文件输出开关关闭（acme.trace.file-logging.enabled=false）：文件键不注入，pattern 缺省不受影响。 */
    @Test
    void shortCircuitsFileKeysWhenFileLoggingDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-svc");
        environment.setProperty("acme.trace.file-logging.enabled", "false");
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.containsProperty("logging.file.name")).isFalse();
        assertThat(environment.containsProperty("logging.logback.rollingpolicy.file-name-pattern")).isFalse();
        // pattern 缺省仍生效（独立开关只管文件键）
        assertThat(environment.getProperty("logging.pattern.console")).contains("%X{traceId}");
    }

    /** 模块开关关闭（acme.trace.enabled=false）：pattern 与文件键均不注入（FR-006、NFR-001）。 */
    @Test
    void shortCircuitsAllKeysWhenModuleDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("acme.trace.enabled", "false");
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.containsProperty("logging.file.name")).isFalse();
        assertThat(environment.containsProperty("logging.pattern.console")).isFalse();
    }

    /** pattern 键显式设置不回退：文件键仍按守卫口径注入（逐项不覆盖，FR-005 异常分支）。 */
    @Test
    void injectsFileKeysEvenWhenPatternsExplicit() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-svc");
        environment.setProperty("logging.pattern.console", "user-console");
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("logging.pattern.console")).isEqualTo("user-console");
        assertThat(environment.getProperty("logging.file.name")).isEqualTo("logs/my-svc.log");
    }
}
