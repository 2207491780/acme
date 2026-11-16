package com.acme.framework.storage.internal;

import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MinIO 直传预签名契约级测试（REQ-20261117-045754 AC-5：mock 客户端 verify，无 Docker 不起容器；
 * 既有 MinioObjectStorageTest Testcontainers 端到端口径保持不动）：
 * PUT 语义 + Content-Type 经 extraHeaders 参与签名（SDK 8.5.12 presignV4 对 canonical headers 签名）+
 * 有效期取 presign-ttl + 返回 URL 原样透出。
 */
class MinioObjectStoragePresignTest {

    private static final String PRESIGNED_URL =
            "https://minio.test/acme/files/2026/11/17/a.png?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc";

    private MinioClient client;
    private StorageProperties properties;
    private MinioObjectStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(MinioClient.class);
        properties = new StorageProperties();
        properties.setEndpoint("https://minio.test");
        properties.setAccessKey("ak");
        properties.setSecretKey("sk");
        properties.setBucket("acme");
        properties.setPresignTtl(Duration.ofMinutes(15));
        storage = new MinioObjectStorage(client, properties);
    }

    @Test
    void presignUploadSignsPutWithContentTypeHeaderAndPresignTtl() throws Exception {
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(PRESIGNED_URL);

        String url = storage.presignUpload(
                ObjectKey.of("files", "a.png", LocalDate.of(2026, 11, 17)), "image/png", 7);

        assertThat(url).isEqualTo(PRESIGNED_URL);
        ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(client).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        // PUT 语义（客户端以 PUT 直传原始字节）+ 桶/对象路径 + 有效期 = presign-ttl
        assertThat(args.method()).isEqualTo(Method.PUT);
        assertThat(args.bucket()).isEqualTo("acme");
        assertThat(args.object()).isEqualTo("files/2026/11/17/a.png");
        assertThat(args.expiry()).isEqualTo((int) Duration.ofMinutes(15).getSeconds());
        // Content-Type 参与签名：extraHeaders 携带（客户端 PUT 必须带同值 Content-Type 头）
        // 注：SDK 经 Multimaps.unmodifiableMultimap 包装且 asMap 值为 Set，与 ImmutableListMultimap
        // 的 List 值不等价，故按 entry 断言而非 Multimap 整体 equals
        assertThat(args.extraHeaders().get("Content-Type")).containsExactly("image/png");
        assertThat(args.extraHeaders().size()).isEqualTo(1);
    }

    @Test
    void presignUploadOmitsContentTypeHeaderWhenBlank() throws Exception {
        // 空 contentType：不注入签名头（客户端可自由携带任意 Content-Type）
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn(PRESIGNED_URL);

        storage.presignUpload(ObjectKey.of("files", "b.bin", LocalDate.of(2026, 11, 17)), "  ", 1);

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(client).getPresignedObjectUrl(captor.capture());
        // Multimap 非标准 Map/Iterable：经 asMap() 断言空
        assertThat(captor.getValue().extraHeaders().asMap()).isEmpty();
        assertThat(captor.getValue().method()).isEqualTo(Method.PUT);
    }
}
