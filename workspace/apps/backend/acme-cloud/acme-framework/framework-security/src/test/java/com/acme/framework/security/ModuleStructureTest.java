package com.acme.framework.security;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块形态核对（FR-026、Modules.md 3.5、AC-020；依赖树明细以构建期 dependency:tree 为准）。
 */
class ModuleStructureTest {

    @Test
    void requiredPackagesExist() throws Exception {
        File classesDir = new File(com.acme.framework.security.config.SecurityAutoConfiguration.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (String pkg : List.of("config", "support", "internal")) {
            assertThat(new File(classesDir, "com/acme/framework/security/" + pkg))
                    .as("包 %s 应存在（Modules.md 3.5）", pkg)
                    .isDirectory();
        }
    }

    @Test
    void noServiceSuffixNaming() throws Exception {
        File classesDir = new File(com.acme.framework.security.config.SecurityAutoConfiguration.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> names = new ArrayList<>();
        collect(classesDir, "", names);
        assertThat(names).noneMatch(name -> name.endsWith("Service"));
    }

    private static void collect(File dir, String prefix, List<String> names) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = prefix.isEmpty() ? child.getName() : prefix + "." + child.getName();
            if (child.isDirectory()) {
                collect(child, name, names);
            } else if (child.getName().endsWith(".class")) {
                names.add(name.substring(0, name.length() - ".class".length()));
            }
        }
    }
}
