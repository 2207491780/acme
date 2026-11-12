package com.acme.framework.trace.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.PatternLayout;
import com.acme.common.contract.TraceConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志 pattern 缺省装配测试（FR-005、AC-006；DQ-004 载体、Backend.md 14.1.1 三字段）。
 */
class TraceLoggingPatternTest {

    private final TraceLoggingPatternEnvironmentPostProcessor processor =
            new TraceLoggingPatternEnvironmentPostProcessor();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /** 缺省注入 console/file 三字段 pattern，service 取 spring.application.name 字面值（DQ-004）。 */
    @Test
    void injectsDefaultPatternWithThreeFields() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-svc");
        processor.postProcessEnvironment(environment, new SpringApplication());
        String console = environment.getProperty("logging.pattern.console");
        String file = environment.getProperty("logging.pattern.file");
        assertThat(console).contains("%X{" + TraceConstants.MDC_TRACE_ID + "}")
                .contains("my-svc")
                .contains("%X{" + TraceConstants.MDC_SPAN_ID + "}");
        assertThat(file).isEqualTo(console);
    }

    /** 用户已显式设置的逐项不覆盖（FR-005 异常分支）。 */
    @Test
    void doesNotOverrideExplicitlySetPatterns() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.pattern.console", "user-console");
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("logging.pattern.console")).isEqualTo("user-console");
        assertThat(environment.getProperty("logging.pattern.file")).contains("%X{traceId}");
    }

    /** 开关关闭短路：不注入（FR-006、NFR-001）。 */
    @Test
    void shortCircuitsWhenModuleDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("acme.trace.enabled", "false");
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.containsProperty("logging.pattern.console")).isFalse();
        assertThat(environment.containsProperty("logging.pattern.file")).isFalse();
    }

    /** AC-006：日志行含三字段且 traceId 与 MDC 一致。 */
    @Test
    void renderedLogContainsTraceServiceAndSpan() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-svc");
        processor.postProcessEnvironment(environment, new SpringApplication());
        String pattern = environment.getProperty("logging.pattern.console");

        MDC.put(TraceConstants.MDC_TRACE_ID, "t-log");
        MDC.put(TraceConstants.MDC_SPAN_ID, "s-log");
        String line = render(pattern, "hello");
        assertThat(line).contains("t-log").contains("my-svc").contains("s-log");
    }

    /** AC-006：MDC 缺值输出空占位，不抛错（FR-005 异常分支）。 */
    @Test
    void missingMdcValuesRenderAsEmptyPlaceholders() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-svc");
        processor.postProcessEnvironment(environment, new SpringApplication());
        String pattern = environment.getProperty("logging.pattern.console");

        String line = render(pattern, "hello");
        assertThat(line).contains("[my-svc]").contains("[]").doesNotContain("null");
    }

    // ---------- 辅助 ----------

    private static String render(String pattern, String message) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        layout.start();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        Logger logger = context.getLogger(TraceLoggingPatternTest.class);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            logger.info(message);
            assertThat(appender.list).hasSize(1);
            return layout.doLayout(appender.list.get(0));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
