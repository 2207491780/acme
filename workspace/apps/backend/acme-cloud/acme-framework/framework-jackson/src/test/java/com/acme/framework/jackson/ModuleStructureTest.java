package com.acme.framework.jackson;

import com.acme.framework.jackson.config.JacksonAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块形态与依赖边界核对（AC-012/AC-013；BR-005/BR-012、NFR-003/NFR-004）。
 * 口径：config/support/internal 必备，interceptor|aspect 可选。
 * 依赖树明细以构建期 dependency:tree 输出为准（实现说明「测试与构建结果」）。
 */
class ModuleStructureTest {

    @Test
    void requiredPackagesExist() {
        File classesDir = mainClassesDir();
        for (String pkg : List.of("config", "support", "internal")) {
            assertThat(new File(classesDir, "com/acme/framework/jackson/" + pkg))
                    .as("包 %s 应存在（BR-005）", pkg)
                    .isDirectory();
        }
    }

    @Test
    void noServiceSuffixNaming() {
        assertThat(mainClassNames())
                .as("不得以 XxxService 命名（AC-013、BR-012）")
                .noneMatch(name -> name.endsWith("Service"));
    }

    @Test
    void noPersistenceReferences() {
        for (byte[] bytes : mainClassBytes()) {
            String content = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(content)
                    .as("不得包含持久化映射（AC-012）")
                    .doesNotContain("jakarta/persistence");
        }
    }

    @Test
    void classpathFreeOfStarterAndJackson2() {
        String classPath = System.getProperty("java.class.path");
        List<String> entries = Arrays.asList(classPath.split(File.pathSeparator));
        assertThat(entries)
                .as("不得引入大而全 starter / Jackson 2 共存（AC-012、BR-009/BR-010）")
                .noneMatch(entry -> entry.contains("jackson2") || entry.contains("spring-boot-starter"));
        assertThat(getClass().getClassLoader().getResource("com/fasterxml/jackson/databind")).isNull();
        assertThat(getClass().getClassLoader().getResource("com/fasterxml/jackson/core")).isNull();
    }

    // ---------- 辅助 ----------

    private static File mainClassesDir() {
        try {
            return new File(JacksonAutoConfiguration.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("解析主代码目录失败", ex);
        }
    }

    private static List<String> mainClassNames() {
        List<String> names = new ArrayList<>();
        collect(mainClassesDir(), "", names);
        return names;
    }

    private static List<byte[]> mainClassBytes() {
        List<byte[]> all = new ArrayList<>();
        for (String name : mainClassNames()) {
            File file = new File(mainClassesDir(), name.replace('.', File.separatorChar) + ".class");
            try {
                all.add(Files.readAllBytes(file.toPath()));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return all;
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
