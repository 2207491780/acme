package com.acme.framework.storage.internal;

import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.StorageObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MinIO 适配器端到端实证（FR-012、AC-012、Q-004：Testcontainers MinIO，
 * 16.2.1 不依赖本地环境——无 Docker 自动跳过，与 platform 侧集成测试同口径）。
 */
@Testcontainers(disabledWithoutDocker = true)
class MinioObjectStorageTest {

    private static final String ACCESS = "acme-test";
    private static final String SECRET = "acme-test-secret";

    @Container
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2024-01-31T20-20-33Z"))
                    .withEnv("MINIO_ROOT_USER", ACCESS)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000);

    private static MinioObjectStorage storage;

    @BeforeAll
    static void connectStorage() {
        StorageProperties properties = new StorageProperties();
        properties.setEndpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        properties.setAccessKey(ACCESS);
        properties.setSecretKey(SECRET);
        properties.setBucket("acme-test");
        storage = MinioObjectStorage.connect(properties);
        createBucket(properties.getEndpoint());
    }

    /** 首次建桶（MinIO 容器默认无桶；经 SDK 管理面建桶）。 */
    private static void createBucket(String endpoint) {
        try {
            io.minio.MinioClient admin = io.minio.MinioClient.builder()
                    .endpoint(endpoint).credentials(ACCESS, SECRET).build();
            admin.makeBucket(io.minio.MakeBucketArgs.builder().bucket("acme-test").build());
        } catch (Exception ex) {
            throw new IllegalStateException("建桶失败", ex);
        }
    }

    @Test
    void putGetExistsRemoveRoundTrip() throws Exception {
        ObjectKey key = ObjectKey.of("p5", "hello.txt");
        storage.put(new StorageObject(key,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5, "text/plain"));
        assertThat(storage.exists(key)).isTrue();
        try (InputStream in = storage.get(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        assertThat(storage.url(key)).isNotBlank();
        storage.remove(key);
        assertThat(storage.exists(key)).isFalse();
    }
}
