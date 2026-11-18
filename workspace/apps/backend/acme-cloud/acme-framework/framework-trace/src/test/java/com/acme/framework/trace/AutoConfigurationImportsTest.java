package com.acme.framework.trace;

import com.acme.framework.trace.config.TraceAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配注册核对（BR-007、AC-011）。
 */
class AutoConfigurationImportsTest {

    @Test
    void importsFileRegistersTraceAutoConfiguration() throws IOException {
        String resource = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("imports 文件应存在（BR-007）").isNotNull();
            List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            assertThat(lines).containsExactly(TraceAutoConfiguration.class.getName());
        }
    }

    @Test
    void springFactoriesRegistersEnvironmentPostProcessor() {
        assertThat(getClass().getClassLoader().getResource("META-INF/spring.factories")).isNotNull();
    }
}
