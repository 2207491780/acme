package com.acme.framework.trace.internal;

import com.acme.common.contract.TraceConstants;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 日志 pattern 缺省装配（FR-005、Backend.md 14.1.1）：经 EnvironmentPostProcessor 注入
 * {@code logging.pattern.console/file} 缺省值（DQ-004）；用户已显式设置的逐项不覆盖（FR-005 异常分支）。
 * service 取 {@code spring.application.name} 内嵌 pattern 字面值，不造 MDC key（DQ-004、BR-013）；
 * traceId/spanId 用 MDC 占位（缺值输出空占位）。SPI 注册载体 {@code META-INF/spring.factories}
 * （非自动装配，架构设计 3.6/取舍 5）。
 */
public final class TraceLoggingPatternEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** 配置键常量与 Boot 4 LoggingSystemProperty 对齐（实现期核实）。 */
    private static final String PATTERN_CONSOLE = "logging.pattern.console";

    private static final String PATTERN_FILE = "logging.pattern.file";

    private static final String APP_NAME = "spring.application.name";

    private static final String MODULE_SWITCH = "acme.trace.enabled";

    private static final String PROPERTY_SOURCE_NAME = "acmeTraceLoggingPattern";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(MODULE_SWITCH, Boolean.class, Boolean.TRUE)) {
            return;
        }
        String service = environment.getProperty(APP_NAME, "");
        String pattern = defaultPattern(service);
        Map<String, Object> props = new HashMap<>();
        if (!environment.containsProperty(PATTERN_CONSOLE)) {
            props.put(PATTERN_CONSOLE, pattern);
        }
        if (!environment.containsProperty(PATTERN_FILE)) {
            props.put(PATTERN_FILE, pattern);
        }
        if (!props.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
        }
    }

    /**
     * 缺省 pattern：含 traceId、service、spanId 三字段（FR-005、Backend.md 14.1.1）。
     * service 为 {@code spring.application.name} 字面值（DQ-004）。
     */
    static String defaultPattern(String service) {
        return "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{" + TraceConstants.MDC_TRACE_ID + "}] ["
                + service + "] [%X{" + TraceConstants.MDC_SPAN_ID + "}] %logger{36} - %msg%n";
    }
}
