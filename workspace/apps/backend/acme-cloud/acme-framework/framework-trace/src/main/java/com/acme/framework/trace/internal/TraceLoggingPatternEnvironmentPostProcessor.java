package com.acme.framework.trace.internal;

import com.acme.common.contract.TraceConstants;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 日志 pattern 与文件滚动缺省装配（FR-005、Backend.md 14.1.1；REQ-20261117-061017 确认⑤）：
 * 经 EnvironmentPostProcessor 注入 {@code logging.pattern.console/file} 缺省值（DQ-004）与
 * {@code logging.file.name} 文件输出缺省（确认⑤：logs/{app}.log + 按天/大小滚动 50MB×7 天/总量 2GB）；
 * 用户已显式设置的逐项不覆盖（FR-005 异常分支；文件键与 pattern 键同守卫口径——逐项判定）。
 * service 取 {@code spring.application.name} 内嵌 pattern 字面值，不造 MDC key（DQ-004、
 * BR-013）。SPI 注册载体 {@code META-INF/spring.factories}（非自动装配，架构设计 3.6/取舍 5）。
 */
public final class TraceLoggingPatternEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** 配置键常量与 Boot 4 LoggingSystemProperty 对齐（实现期核实）。 */
    private static final String PATTERN_CONSOLE = "logging.pattern.console";

    private static final String PATTERN_FILE = "logging.pattern.file";

    private static final String APP_NAME = "spring.application.name";

    private static final String MODULE_SWITCH = "acme.trace.enabled";

    private static final String FILE_LOGGING_SWITCH = "acme.trace.file-logging.enabled";

    private static final String FILE_NAME = "logging.file.name";

    private static final String ROLLING_FILE_NAME_PATTERN = "logging.logback.rollingpolicy.file-name-pattern";

    private static final String ROLLING_MAX_FILE_SIZE = "logging.logback.rollingpolicy.max-file-size";

    private static final String ROLLING_MAX_HISTORY = "logging.logback.rollingpolicy.max-history";

    private static final String ROLLING_TOTAL_SIZE_CAP = "logging.logback.rollingpolicy.total-size-cap";

    private static final String PROPERTY_SOURCE_NAME = "acmeTraceLoggingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(MODULE_SWITCH, Boolean.class, Boolean.TRUE)) {
            return;
        }
        String service = environment.getProperty(APP_NAME, "");
        Map<String, Object> props = new HashMap<>();
        String pattern = defaultPattern(service);
        if (!environment.containsProperty(PATTERN_CONSOLE)) {
            props.put(PATTERN_CONSOLE, pattern);
        }
        if (!environment.containsProperty(PATTERN_FILE)) {
            props.put(PATTERN_FILE, pattern);
        }
        if (environment.getProperty(FILE_LOGGING_SWITCH, Boolean.class, Boolean.TRUE)) {
            // 逐项不覆盖（同 pattern 键口径：用户已设的键跳过，未设的键照常注入）
            String app = service == null || service.isBlank() ? "application" : service;
            if (!environment.containsProperty(FILE_NAME)) {
                props.put(FILE_NAME, "logs/" + app + ".log");
            }
            if (!environment.containsProperty(ROLLING_FILE_NAME_PATTERN)) {
                props.put(ROLLING_FILE_NAME_PATTERN, "logs/" + app + ".%d{yyyy-MM-dd}.%i.log");
            }
            if (!environment.containsProperty(ROLLING_MAX_FILE_SIZE)) {
                props.put(ROLLING_MAX_FILE_SIZE, "50MB");
            }
            if (!environment.containsProperty(ROLLING_MAX_HISTORY)) {
                props.put(ROLLING_MAX_HISTORY, "7");
            }
            if (!environment.containsProperty(ROLLING_TOTAL_SIZE_CAP)) {
                props.put(ROLLING_TOTAL_SIZE_CAP, "2GB");
            }
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
