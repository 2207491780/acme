package com.acme.framework.stream;

import com.acme.framework.stream.config.StreamAutoConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块形态与依赖边界核对（FR-030/AC-028、Modules.md 3.5）。
 * config/support/internal 必备、interceptor 可选（不再要求四包全齐/空壳）。
 */
class ModuleStructureTest {

    @Test
    void requiredPackagesExist() {
        File classesDir = mainClassesDir();
        for (String pkg : List.of("config", "support", "internal")) {
            assertThat(new File(classesDir, "com/acme/framework/stream/" + pkg))
                    .as("包 %s 应存在（Modules.md 3.5）", pkg)
                    .isDirectory();
        }
    }

    @Test
    void noServiceSuffixNaming() {
        assertThat(mainClassNames()).noneMatch(name -> name.endsWith("Service"));
    }

    // ---------- 辅助 ----------

    private static File mainClassesDir() {
        try {
            return new File(StreamAutoConfiguration.class.getProtectionDomain()
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
