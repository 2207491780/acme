package com.acme.framework.storage.internal;

import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.StorageError;
import com.acme.framework.storage.support.StorageObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地盘适配器测试（REQ-20261114-071607 AC-6：按 ObjectKey 落盘、目录结构确定；
 * remove 幂等；根目录缺失显式失败 SYS-15-0006；目录逃逸拒绝）。
 */
class LocalObjectStorageTest {

    @TempDir
    Path tempDir;

    private LocalObjectStorage storage;

    @BeforeEach
    void connect() {
        storage = LocalObjectStorage.connect(properties(tempDir));
    }

    private static StorageProperties properties(Path root) {
        StorageProperties properties = new StorageProperties();
        properties.setType(StorageProperties.Type.LOCAL);
        properties.getLocal().setRootDir(root.toAbsolutePath().toString());
        properties.getLocal().setUrlBase("http://files.example.com/acme");
        return properties;
    }

    @Test
    void putLandsAtDeterministicObjectKeyPath() throws Exception {
        // AC-6：落盘路径 = root/namespace/yyyy/MM/dd/name，目录结构确定
        LocalDate date = LocalDate.of(2026, 11, 14);
        ObjectKey key = ObjectKey.of("files", "a1.txt", date);
        storage.put(new StorageObject(key,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5, "text/plain"));

        Path expected = tempDir.resolve("files/2026/11/14/a1.txt");
        assertThat(expected).hasContent("hello");
        assertThat(storage.exists(key)).isTrue();
    }

    @Test
    void getReturnsOriginalBytes() throws Exception {
        ObjectKey key = ObjectKey.of("files", "a2.bin");
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        storage.put(new StorageObject(key, new ByteArrayInputStream(payload), payload.length, null));

        try (InputStream in = storage.get(key)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void removeIsIdempotent() {
        // 门面口径：删除不存在视为幂等成功
        ObjectKey key = ObjectKey.of("files", "gone.txt");
        storage.put(new StorageObject(key,
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain"));
        storage.remove(key);
        assertThat(storage.exists(key)).isFalse();
        storage.remove(key);
        storage.remove(ObjectKey.of("files", "never-existed.txt"));
    }

    @Test
    void getMissingObjectFailsExplicitly() {
        assertThatThrownBy(() -> storage.get(ObjectKey.of("files", "absent.txt")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(StorageError.OBJECT_NOT_FOUND));
    }

    @Test
    void urlIsUrlBaseJoinedWithObjectKeyPath() {
        // url 语义 = acme.storage.local.url-base 拼接 ObjectKey
        ObjectKey key = ObjectKey.of("files", "u.txt", LocalDate.of(2026, 11, 14));
        assertThat(storage.url(key))
                .isEqualTo("http://files.example.com/acme/files/2026/11/14/u.txt");
    }

    @Test
    void blankUrlBaseFallsBackToRelativePath() {
        StorageProperties properties = properties(tempDir);
        properties.getLocal().setUrlBase("");
        LocalObjectStorage bare = LocalObjectStorage.connect(properties);
        assertThat(bare.url(ObjectKey.of("files", "b.txt", LocalDate.of(2026, 1, 2))))
                .isEqualTo("files/2026/01/02/b.txt");
    }

    @Test
    void missingRootDirFailsExplicitly() {
        StorageProperties properties = new StorageProperties();
        properties.setType(StorageProperties.Type.LOCAL);
        assertThatThrownBy(() -> LocalObjectStorage.connect(properties))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(StorageError.LOCAL_ROOT_DIR_MISSING));
    }

    @Test
    void pathTraversalIsRejected() {
        ObjectKey escaping = ObjectKey.of("../escape", "evil.txt");
        assertThatThrownBy(() -> storage.exists(escaping))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
