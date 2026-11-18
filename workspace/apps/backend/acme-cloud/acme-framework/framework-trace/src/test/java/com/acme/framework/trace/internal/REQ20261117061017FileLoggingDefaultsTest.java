package com.acme.framework.trace.internal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261117-061017 AC-9 文件日志缺省装配单元测试（framework-trace，纯 JUnit；
 * 构造先例 {@code TraceLoggingPatternTest}——MockEnvironment + EnvironmentPostProcessor 直调）。
 *
 * <p>覆盖：EnvironmentPostProcessor 注入 {@code logging.file.name} 与 logback 滚动策略缺省键
 * （确认⑤：logs/{app}.log、按天+大小滚动 file-name-pattern、max-file-size 50MB、max-history 7、
 * total-size-cap 2GB）；用户显式配置逐项不覆盖；开关 {@code acme.trace.file-logging.enabled}
 * （默认 true）关闭时不注入且不影响既有 pattern 键行为。</p>
 *
 * <p>口径说明（目标态假设，TL 裁决后同步调整）：</p>
 * <ul>
 *   <li>AMB-1 载体假设：file appender 缺省键由既有
 *       {@code TraceLoggingPatternEnvironmentPostProcessor} 同类承载（变更清单「EnvironmentPostProcessor
 *       增 file appender 缺省键」）；若交付为独立 EnvironmentPostProcessor 类，本测试改为实例化该类。</li>
 *   <li>AMB-2 键名假设（与 Boot 4 logback 配置键对齐）：logging.file.name、
 *       logging.logback.rollingpolicy.file-name-pattern / max-file-size / max-history / total-size-cap；
 *       逐项不覆盖口径同 pattern 键（用户已设的键跳过，未设的键照常注入）。</li>
 *   <li>AMB-3 值字面量假设：{@code logs/{spring.application.name}.log} 与
 *       {@code logs/{app}.%d{yyyy-MM-dd}.%i.log} 中 {app} 为 spring.application.name 字面替换
 *       （pattern 键「service 字面值」先例同型）。</li>
 *   <li>AMB-4 文件开关 {@code acme.trace.file-logging.enabled} 独立于既有 {@code acme.trace.enabled}
 *       （pattern 键开关）：前者关闭仅跳过 file 键，pattern 缺省行为保持。</li>
 *   <li>AMB-5 spring.application.name 缺失时的 file.name 行为契约未定义，不在用例面。</li>
 * </ul>
 */
class REQ20261117061017FileLoggingDefaultsTest {

    private final TraceLoggingPatternEnvironmentPostProcessor processor =
            new TraceLoggingPatternEnvironmentPostProcessor();

    /** AC-9：未显式配置 → 注入 file.name 与滚动策略五键缺省值（{app} 字面替换，AMB-3）。 */
    @Test
    void should_injectFileLoggingDefaults_when_userNotConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "acme-demo");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("logging.file.name"))
                .as("应注入 logging.file.name=logs/{app}.log").isEqualTo("logs/acme-demo.log");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.file-name-pattern"))
                .as("应注入按天+大小滚动 file-name-pattern")
                .isEqualTo("logs/acme-demo.%d{yyyy-MM-dd}.%i.log");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.max-file-size"))
                .isEqualTo("50MB");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.max-history"))
                .isEqualTo("7");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.total-size-cap"))
                .isEqualTo("2GB");
    }

    /** AC-9：用户显式配置 → 逐项不覆盖（file.name 与 file-name-pattern 保持原值，其余键照常注入）。 */
    @Test
    void should_keepExplicitFileLogging_when_userConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "acme-demo");
        environment.setProperty("logging.file.name", "custom/app.log");
        environment.setProperty("logging.logback.rollingpolicy.file-name-pattern", "custom/pattern.log");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("logging.file.name"))
                .as("显式 logging.file.name 不得被覆盖").isEqualTo("custom/app.log");
        assertThat(environment.getProperty("logging.logback.rollingpolicy.file-name-pattern"))
                .as("显式 file-name-pattern 不得被覆盖").isEqualTo("custom/pattern.log");
        // 用户未设的滚动键逐项照常注入（AMB-2 同 pattern 键口径）
        assertThat(environment.getProperty("logging.logback.rollingpolicy.max-file-size"))
                .isEqualTo("50MB");
    }

    /** AC-9：文件日志开关关闭 → 不注入 file 键；pattern 缺省行为不受影响（AMB-4）。 */
    @Test
    void should_skipFileLoggingDefaults_when_switchDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "acme-demo");
        environment.setProperty("acme.trace.file-logging.enabled", "false");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.containsProperty("logging.file.name"))
                .as("开关关闭时不得注入 logging.file.name").isFalse();
        assertThat(environment.containsProperty("logging.logback.rollingpolicy.file-name-pattern"))
                .isFalse();
        // pattern 键为既有行为（acme.trace.enabled 独立开关，默认开）：保持不变
        assertThat(environment.getProperty("logging.pattern.console")).isNotNull();
        assertThat(environment.getProperty("logging.pattern.file")).isNotNull();
    }
}
