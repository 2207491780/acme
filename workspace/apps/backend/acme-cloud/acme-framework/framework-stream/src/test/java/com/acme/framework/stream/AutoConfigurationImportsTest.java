package com.acme.framework.stream;

import com.acme.framework.stream.config.StreamAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AutoConfiguration.imports 注册核对（FR-030、Modules.md 3.5 自动装配）。
 */
class AutoConfigurationImportsTest {

    @Test
    void registeredInImports() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).isNotNull();
            List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
            assertThat(lines).containsExactly(StreamAutoConfiguration.class.getName());
        }
    }
}
