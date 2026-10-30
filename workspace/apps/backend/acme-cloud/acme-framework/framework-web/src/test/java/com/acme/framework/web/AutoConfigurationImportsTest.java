package com.acme.framework.web;

import com.acme.framework.web.config.WebAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配注册核对（Modules.md 3.5、AC-020）。
 */
class AutoConfigurationImportsTest {

    @Test
    void importsFileRegistersAutoConfiguration() throws Exception {
        String resource = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("imports 文件应存在").isNotNull();
            List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            assertThat(lines).containsExactly(WebAutoConfiguration.class.getName());
        }
    }
}
