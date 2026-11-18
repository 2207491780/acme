package com.acme.framework.storage.config;

import com.acme.common.core.BizException;
import com.acme.framework.storage.internal.MinioObjectStorage;
import com.acme.framework.storage.internal.OssObjectStorage;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-20261114-071607 本地盘实现装配与落盘布局测试（ApplicationContextRunner 切片口径，
 * 照抄 {@code StorageSwitchTest}；不连任何真实对象存储/真库）。
 *
 * <p>覆盖：AC-7（无本地盘实现配置时缺省 type=local；显式 type=minio/oss 装配对应实现——
 * 仅断言装配类型）、AC-6（框架侧：type=local 时对象按 ObjectKey 落盘，目录结构确定，
 * url 语义为 {@code acme.storage.local.url-base} 拼接）。</p>
 *
 * <p>口径说明（契约歧义就地标注，实施对齐时同步调整）：</p>
 * <ul>
 *   <li>缺省装配断言采用"非 MinIO/OSS 实例 + StorageProperties.Type.LOCAL"组合：
 *       LocalObjectStorage 的包名契约未指明（MinIO/OSS 实现位于 internal 包，本用例不猜测
 *       新实现 FQCN，以行为用例佐证本地盘语义）。</li>
 *   <li>本地盘目录结构按 root-dir + ObjectKey.path()（support/ObjectKeyTest 口径：
 *       namespace/yyyy/MM/dd/name）；本地盘是否将 bucket 作为一级子目录契约未指明，
 *       用例按"不嵌 bucket 层级"断言。</li>
 *   <li>url 断言 startsWith(url-base)（斜杠拼接等细节属实现语义，契约未指明）。</li>
 *   <li>type=local 时不要求 endpoint/access-key/secret-key（本地 dev 无对象存储中间件，
 *       凭据校验应随类型条件化）——用例隐式校验该约束。</li>
 * </ul>
 */
class REQ20261114071607LocalStorageAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class));

    // ---------- AC-7：无本地盘实现配置时缺省 type=local ----------

    @Test
    void should_assembleLocalStorageByDefault_when_noTypeConfigured() {
        runner.withPropertyValues(
                        "acme.storage.local.root-dir=" + tempRoot(),
                        "acme.storage.local.url-base=http://files.localhost/acme")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    assertThat(storage).isNotInstanceOf(MinioObjectStorage.class);
                    assertThat(storage).isNotInstanceOf(OssObjectStorage.class);
                    assertThat(context.getBean(StorageProperties.class).getType())
                            .isEqualTo(StorageProperties.Type.LOCAL);
                });
    }

    // ---------- AC-7：显式 type=minio/oss 装配对应实现（仅断言装配类型，不连真库） ----------

    @Test
    void should_assembleMinioImpl_when_typeMinioExplicit() {
        runner.withPropertyValues(
                        "acme.storage.type=minio",
                        "acme.storage.endpoint=http://127.0.0.1:9000",
                        "acme.storage.access-key=k",
                        "acme.storage.secret-key=s")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(MinioObjectStorage.class);
                });
    }

    @Test
    void should_assembleOssImpl_when_typeOssExplicit() {
        runner.withPropertyValues(
                        "acme.storage.type=oss",
                        "acme.storage.endpoint=http://oss.example.com",
                        "acme.storage.access-key=k",
                        "acme.storage.secret-key=s")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(OssObjectStorage.class);
                });
    }

    // ---------- AC-6（框架侧）：type=local 时对象按 ObjectKey 落盘，目录结构确定 ----------

    @Test
    void should_persistObjectUnderRootDirWithObjectKeyLayout_when_localPutGetRemove() throws IOException {
        String rootDir = tempRoot();
        runner.withPropertyValues(
                        "acme.storage.type=local",
                        "acme.storage.local.root-dir=" + rootDir,
                        "acme.storage.local.url-base=http://files.localhost/acme")
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    // ObjectKey 路径约定（support/ObjectKeyTest）：namespace/yyyy/MM/dd/name
                    ObjectKey key = ObjectKey.of("req-it", "report.txt", LocalDate.of(2026, 11, 14));
                    byte[] content = "req-it-local-bytes".getBytes(StandardCharsets.UTF_8);

                    storage.put(new StorageObject(key,
                            new ByteArrayInputStream(content), content.length, "text/plain"));

                    // 目录结构确定：root-dir + ObjectKey.path()
                    Path expected = Path.of(rootDir).resolve(key.path());
                    assertThat(expected).exists();
                    assertThat(Files.readAllBytes(expected)).isEqualTo(content);
                    assertThat(storage.exists(key)).isTrue();

                    // url 语义：acme.storage.local.url-base 拼接
                    assertThat(storage.url(key)).startsWith("http://files.localhost/acme");

                    try (InputStream in = storage.get(key)) {
                        assertThat(in.readAllBytes()).isEqualTo(content);
                    }

                    // remove 幂等语义支撑（文件服务删除链路依赖）
                    storage.remove(key);
                    assertThat(storage.exists(key)).isFalse();
                    assertThat(expected).doesNotExist();
                });
    }

    // ---------- 边界：本地盘 get 不存在对象 → 显式业务异常（SDK 异常统一转 BizException 门面语义） ----------

    @Test
    void should_failExplicitlyWithBizException_when_localGetMissingObject() {
        runner.withPropertyValues(
                        "acme.storage.type=local",
                        "acme.storage.local.root-dir=" + tempRoot(),
                        "acme.storage.local.url-base=http://files.localhost/acme")
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    ObjectKey missing = ObjectKey.of("req-it", "missing.txt", LocalDate.of(2026, 11, 14));
                    assertThatThrownBy(() -> storage.get(missing)).isInstanceOf(BizException.class);
                });
    }

    // ---------- 支撑 ----------

    private static String tempRoot() {
        try {
            return Files.createTempDirectory("req20261114071607-local-assembly").toString();
        } catch (IOException ex) {
            throw new IllegalStateException("创建临时根目录失败", ex);
        }
    }
}
