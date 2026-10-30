package com.acme.framework.observability;

import com.acme.framework.observability.config.ObservabilityAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块形态核对（FR-024/AC-023、Modules.md 3.5；observability 无 aspect/interceptor 不设空壳包）。
 */
class ObservabilityModuleFormTest {

    @Test
    void requiredPackagesExist() throws URISyntaxException {
        File classesDir = mainClassesDir();
        for (String pkg : List.of("config", "support", "internal")) {
            assertThat(new File(classesDir, "com/acme/framework/observability/" + pkg)).isDirectory();
        }
    }

    @Test
    void registeredInImports() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).isNotNull();
            List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
            assertThat(lines).containsExactly(ObservabilityAutoConfiguration.class.getName());
        }
    }

    @Test
    void noServiceSuffixNaming() {
        List<String> names = new ArrayList<>();
        collect(mainClassesDir(), "", names);
        assertThat(names).noneMatch(name -> name.endsWith("Service"));
    }

    private static File mainClassesDir() {
        try {
            return new File(ObservabilityAutoConfiguration.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("解析主代码目录失败", ex);
        }
    }

    private static void collect(File dir, String namePrefix, List<String> names) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = namePrefix.isEmpty() ? child.getName() : namePrefix + "." + child.getName();
            if (child.isDirectory()) {
                collect(child, name, names);
            } else if (child.getName().endsWith(".class")) {
                names.add(name.substring(0, name.length() - ".class".length()));
            }
        }
    }
}
