package com.acme.framework.storage;

import com.acme.framework.storage.config.StorageAutoConfiguration;
import com.acme.framework.storage.support.StorageError;
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
 * 模块形态与错误码段位核对（FR-024/AC-023、Modules.md 3.5、DP-008；storage 无 aspect/interceptor 不设空壳包）。
 */
class StorageModuleFormTest {

    @Test
    void requiredPackagesExist() throws URISyntaxException {
        File classesDir = mainClassesDir();
        for (String pkg : List.of("config", "support", "internal")) {
            assertThat(new File(classesDir, "com/acme/framework/storage/" + pkg)).isDirectory();
        }
    }

    @Test
    void registeredInImports() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).isNotNull();
            List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
            assertThat(lines).containsExactly(StorageAutoConfiguration.class.getName());
        }
    }

    @Test
    void noServiceSuffixNaming() {
        List<String> names = new ArrayList<>();
        collect(mainClassesDir(), "", names);
        assertThat(names).noneMatch(name -> name.endsWith("Service"));
    }

    @Test
    void errorCodesLockedToSys15Segment() {
        assertThat(StorageError.OBJECT_NOT_FOUND.code()).isEqualTo("SYS-15-0001");
        assertThat(StorageError.CREDENTIAL_INVALID.code()).isEqualTo("SYS-15-0002");
        assertThat(StorageError.CREDENTIAL_MISSING.code()).isEqualTo("SYS-15-0003");
        assertThat(StorageError.NETWORK_FAILURE.code()).isEqualTo("SYS-15-0004");
        assertThat(StorageError.BUCKET_UNAVAILABLE.code()).isEqualTo("SYS-15-0005");
    }

    private static File mainClassesDir() {
        try {
            return new File(StorageAutoConfiguration.class.getProtectionDomain()
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
