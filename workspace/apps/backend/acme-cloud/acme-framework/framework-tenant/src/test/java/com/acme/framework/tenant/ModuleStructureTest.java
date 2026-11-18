package com.acme.framework.tenant;

import com.acme.framework.tenant.config.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块形态核对（FR-030/AC-028、Modules.md 3.5）。
 */
class ModuleStructureTest {

    @Test
    void requiredPackagesExist() {
        File classesDir = mainClassesDir();
        for (String pkg : List.of("config", "support", "internal")) {
            assertThat(new File(classesDir, "com/acme/framework/tenant/" + pkg))
                    .as("包 %s 应存在（Modules.md 3.5）", pkg)
                    .isDirectory();
        }
    }

    @Test
    void noServiceSuffixNaming() {
        assertThat(mainClassNames()).noneMatch(name -> name.endsWith("Service"));
    }

    @Test
    void noBusinessTableMapping() {
        // 演示实体仅测试域；主代码不得含实体映射（Modules.md 3.5 数据边界）
        for (byte[] bytes : mainClassBytes()) {
            String content = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(content).doesNotContain("jakarta/persistence/Entity");
        }
    }

    // ---------- 辅助 ----------

    private static File mainClassesDir() {
        try {
            return new File(TenantAutoConfiguration.class.getProtectionDomain()
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
