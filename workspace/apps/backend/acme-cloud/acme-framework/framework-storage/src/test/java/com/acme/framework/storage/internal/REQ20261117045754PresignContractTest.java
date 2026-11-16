package com.acme.framework.storage.internal;

import com.acme.common.core.BizException;
import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.StorageError;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261117-045754 直传预签名契约级测试（纯 mock，无容器；MinIO 相关测试一律跳过口径下的
 * mock 契约级覆盖口径）。
 *
 * <p>AC→用例映射：</p>
 * <ul>
 *   <li>AC-5 MinIO：{@link #minioPresignUploadSignsPutUrlWithPutMethodAndPresignTtlExpiry}
 *       —— mock {@code MinioClient}（子类录制桩）verify {@code getPresignedObjectUrl} 以
 *       {@code Method.PUT} 调用且 expiry=presignTtl（秒）；{@link #minioUrlKeepsGetDownloadPresign}
 *       —— 既有 {@code url()} GET 下载预签名不回归（AC-5 附带面）。</li>
 *   <li>AC-5 OSS：{@link #ossPresignUploadSignsPutUrlWithContentType}
 *       —— mock {@code OSS}（JDK Proxy，沿用 {@code OssObjectStorageTest} 桩件风格）verify
 *       {@code GeneratePresignedUrlRequest} method=PUT + contentType。</li>
 *   <li>AC-5 Local 默认：{@link #localPresignUploadFailsWithPresignUnsupported}
 *       —— 默认抛 PRESIGN_UNSUPPORTED。</li>
 * </ul>
 *
 * <p>口径说明（契约歧义就地标注 AMB，实施对齐时同步调整）：</p>
 * <ul>
 *   <li>AMB-7 framework-storage 测试域无 mockito 依赖（委派单禁引新依赖）：MinIO 用匿名子类覆写
 *       {@code getPresignedObjectUrl} 做录制桩（假定 minio 8.5.12 的 {@code MinioClient} 非 final 且
 *       {@code MinioClient(Builder)} 构造器可访问）；OSS 沿用既有 JDK Proxy 桩风格。</li>
 *   <li>AMB-8 presignUpload 固定签名：{@code presignUpload(ObjectKey, String contentType, long size)}
 *       （契约变更清单）；MinIO expiry 断言 = presignTtl 秒数（委派单口径；既有 url() 用
 *       {@code (int) Math.max(seconds, 1)} 同式，7min=420 不受下限影响）。
 *       {@code GetPresignedObjectUrlArgs} 访问器 method()/bucket()/object()/expiry() 按 minio 8.5.x。</li>
 *   <li>AMB-6 MinIO Content-Type 参与签名的机制（extraQueryParams 等）契约未指明，委派单仅要求
 *       PUT + expiry → 不断言；OSS contentType 经 {@code GeneratePresignedUrlRequest.setContentType}
 *       断言（契约明示 PUT + ContentType，仅请求对象重载可承载 contentType）。</li>
 *   <li>AMB-1 Local 拒绝码枚举常量名按契约变更清单：{@code StorageError.PRESIGN_UNSUPPORTED}
 *       （SYS-15 段续编）。</li>
 * </ul>
 */
class REQ20261117045754PresignContractTest {

    private static final String PRESIGNED_URL = "https://storage.integration.invalid/acme/put-presigned";

    @TempDir
    Path tempDir;

    // ---------- AC-5：MinIO presignUpload → Method.PUT + expiry=presignTtl ----------

    @Test
    void minioPresignUploadSignsPutUrlWithPutMethodAndPresignTtlExpiry() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("acme");
        properties.setPresignTtl(Duration.ofMinutes(7));
        RecordingMinioClient client = new RecordingMinioClient();
        MinioObjectStorage storage = new MinioObjectStorage(client, properties);
        ObjectKey key = ObjectKey.of("files", "report.png", LocalDate.of(2026, 11, 17));

        String url = storage.presignUpload(key, "image/png", 128L);

        assertThat(url).as("presignUpload 应透传 SDK 预签名 URL").isEqualTo(PRESIGNED_URL);
        assertThat(client.presignCalls).as("presignUpload 应恰好一次 getPresignedObjectUrl 调用")
                .hasSize(1);
        GetPresignedObjectUrlArgs args = client.presignCalls.get(0);
        assertThat(args.method()).isEqualTo(Method.PUT);
        assertThat(args.expiry()).as("expiry=presignTtl（秒）").isEqualTo(420);
        assertThat(args.bucket()).isEqualTo("acme");
        assertThat(args.object()).as("对象键 = ObjectKey 全路径").isEqualTo("files/2026/11/17/report.png");
    }

    /** AC-5 附带面：既有 url() GET 下载预签名语义不被 presignUpload 变更（契约确认记录①：url 仅 GET）。 */
    @Test
    void minioUrlKeepsGetDownloadPresign() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("acme");
        properties.setPresignTtl(Duration.ofMinutes(15));
        RecordingMinioClient client = new RecordingMinioClient();
        MinioObjectStorage storage = new MinioObjectStorage(client, properties);

        String url = storage.url(ObjectKey.of("files", "report.png", LocalDate.of(2026, 11, 17)));

        assertThat(url).isEqualTo(PRESIGNED_URL);
        assertThat(client.presignCalls).hasSize(1);
        assertThat(client.presignCalls.get(0).method()).isEqualTo(Method.GET);
    }

    // ---------- AC-5：OSS presignUpload → GeneratePresignedUrlRequest PUT + contentType ----------

    @Test
    void ossPresignUploadSignsPutUrlWithContentType() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("acme");
        properties.setPresignTtl(Duration.ofMinutes(7));
        OssRecordingOss oss = new OssRecordingOss();
        OssObjectStorage storage = new OssObjectStorage(oss.stub(), properties);
        Date before = new Date();
        ObjectKey key = ObjectKey.of("files", "report.png", LocalDate.of(2026, 11, 17));

        String url = storage.presignUpload(key, "image/png", 128L);

        assertThat(url).as("presignUpload 应透传 SDK 预签名 URL").isEqualTo(PRESIGNED_URL);
        assertThat(oss.presignRequests).as("应经 GeneratePresignedUrlRequest 重载发起预签名").hasSize(1);
        GeneratePresignedUrlRequest request = oss.presignRequests.get(0);
        assertThat(request.getMethod()).isEqualTo(HttpMethod.PUT);
        assertThat(request.getContentType()).as("Content-Type 参与签名").isEqualTo("image/png");
        assertThat(request.getBucketName()).isEqualTo("acme");
        assertThat(request.getKey()).as("对象键 = ObjectKey 全路径").isEqualTo("files/2026/11/17/report.png");
        assertThat(request.getExpiration()).as("有效期 = now + presignTtl").isAfter(before);
    }

    // ---------- AC-5：Local 默认抛 PRESIGN_UNSUPPORTED ----------

    @Test
    void localPresignUploadFailsWithPresignUnsupported() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir, "");

        assertThatThrownBy(() -> storage.presignUpload(
                        ObjectKey.of("files", "report.png", LocalDate.of(2026, 11, 17)), "image/png", 128L))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(StorageError.PRESIGN_UNSUPPORTED));
        // 机械修复（backend-dev，tester 并行交付签名对接）：Files.walk 产出含根目录自身，
        // 空目录 count=1 非零；「不得触碰文件系统」语义按常规文件计数断言
        try (var paths = Files.walk(tempDir)) {
            assertThat(paths.filter(Files::isRegularFile).count())
                    .as("Local presign 拒绝不得触碰文件系统").isZero();
        }
    }

    // ---------- 桩件（无 mockito 依赖，AMB-7） ----------

    /** MinIO 录制桩：子类覆写 getPresignedObjectUrl（MinioClient 非 final 假定，AMB-7）。 */
    private static final class RecordingMinioClient extends MinioClient {

        private final List<GetPresignedObjectUrlArgs> presignCalls = new ArrayList<>();

        private RecordingMinioClient() {
            // 机械修复（backend-dev，tester 并行交付签名对接）：MinioClient 构造器形参为 MinioAsyncClient，
            // 须先 builder().build() 再传 super——builder() 仅装配无网络调用，语义与 AMB-7 假定一致
            super(MinioClient.builder()
                    .endpoint("https://storage.integration.invalid")
                    .credentials("acme-ak", "acme-sk")
                    .build());
        }

        @Override
        public String getPresignedObjectUrl(GetPresignedObjectUrlArgs args) {
            presignCalls.add(args);
            return PRESIGNED_URL;
        }
    }

    /** OSS 录制桩：JDK Proxy（沿用 {@code OssObjectStorageTest} 风格；记录请求对象重载）。 */
    private static final class OssRecordingOss {

        private final List<GeneratePresignedUrlRequest> presignRequests = new ArrayList<>();

        private OSS stub() {
            return (OSS) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {OSS.class},
                    (proxy, method, args) -> {
                        if ("generatePresignedUrl".equals(method.getName())) {
                            if (args != null && args.length == 1
                                    && args[0] instanceof GeneratePresignedUrlRequest request) {
                                presignRequests.add(request);
                            }
                            return new URL(PRESIGNED_URL);
                        }
                        if ("toString".equals(method.getName())) {
                            return "OSS-recording-stub";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        return null;
                    });
        }
    }
}
