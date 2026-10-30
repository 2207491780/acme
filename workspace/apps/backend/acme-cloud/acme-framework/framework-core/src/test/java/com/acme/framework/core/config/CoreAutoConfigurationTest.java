package com.acme.framework.core.config;

import com.acme.framework.core.internal.ApplicationContextRegistry;
import com.acme.framework.core.support.CoreExecutor;
import com.acme.framework.core.support.TransactionEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配注册测试（AC-009；BR-004、Modules.md 3.5）。
 */
class CoreAutoConfigurationTest {

    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void importsFileRegistersCoreAutoConfiguration() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(IMPORTS_PATH)) {
            assertThat(in).isNotNull();
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            assertThat(lines).containsExactly(CoreAutoConfiguration.class.getName());
        }
    }

    @Test
    void registeredEntryActivatesWithoutExtraAnnotations() {
        List<Class<?>> autoConfigurationClasses = loadImports();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(autoConfigurationClasses.toArray(Class[]::new)))
                .run(context -> {
                    assertThat(context).hasSingleBean(CoreExecutor.class);
                    assertThat(context).hasSingleBean(TransactionEventPublisher.class);
                    assertThat(context).hasSingleBean(ApplicationContextRegistry.class);
                });
    }

    private List<Class<?>> loadImports() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(IMPORTS_PATH)) {
            assertThat(in).isNotNull();
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            List<Class<?>> loaded = new ArrayList<>();
            for (String name : lines) {
                try {
                    loaded.add(Class.forName(name));
                } catch (ClassNotFoundException ex) {
                    throw new IllegalStateException("装配项类缺失：" + name, ex);
                }
            }
            return loaded;
        } catch (Exception ex) {
            throw new IllegalStateException("读取 AutoConfiguration.imports 失败", ex);
        }
    }
}
