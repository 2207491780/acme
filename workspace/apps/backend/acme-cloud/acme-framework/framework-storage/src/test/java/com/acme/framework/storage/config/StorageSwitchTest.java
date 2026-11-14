package com.acme.framework.storage.config;

import com.acme.common.core.BizException;
import com.acme.framework.storage.internal.LocalObjectStorage;
import com.acme.framework.storage.support.StorageError;
import com.acme.framework.storage.internal.MinioObjectStorage;
import com.acme.framework.storage.internal.OssObjectStorage;
import com.acme.framework.storage.support.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-024/AC-024、BR-003/004；凭证缺失显式失败 SYS-15-0003；
 * 装配选择 REQ-20261114-071607 AC-7：type=local 缺省，显式 minio/oss 装配对应实现）。
 */
class StorageSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class));

    @Test
    void disabledByOneLineConfigAssemblesNothing() {
        runner.withPropertyValues("acme.storage.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ObjectStorage.class);
                    assertThat(context).doesNotHaveBean(StorageProperties.class);
                });
    }

    @Test
    void zeroConfigDefaultsToLocalAssembly(@TempDir Path tempDir) {
        // AC-7：无配置时缺省 type=local，装配 LocalObjectStorage（local 无凭证诉求）
        runner.withPropertyValues("acme.storage.local.root-dir=" + tempDir.toAbsolutePath())
                .run(context -> {
                    StorageProperties properties = context.getBean(StorageProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getType()).isEqualTo(StorageProperties.Type.LOCAL);
                    assertThat(properties.getBucket()).isEqualTo("acme");
                    assertThat(properties.getPresignTtl()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.getUpload().getMaxSize().toMegabytes()).isEqualTo(10);
                    assertThat(properties.getUpload().getRetentionDays()).isEqualTo(7);
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(LocalObjectStorage.class);
                });
    }

    @Test
    void explicitMinioTypeAssemblesMinioAdapter() {
        // AC-7：显式 type=minio 装配既有 MinIO 实现（凭证校验口径不变）
        runner.withPropertyValues(
                        "acme.storage.type=minio",
                        "acme.storage.endpoint=http://127.0.0.1:9000",
                        "acme.storage.access-key=k",
                        "acme.storage.secret-key=s")
                .run(context -> {
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(MinioObjectStorage.class);
                });
    }

    @Test
    void explicitOssTypeAssemblesOssAdapter() {
        // AC-7：显式 type=oss 装配既有 OSS 实现
        runner.withPropertyValues(
                        "acme.storage.type=oss",
                        "acme.storage.endpoint=http://oss.example.com",
                        "acme.storage.access-key=k",
                        "acme.storage.secret-key=s",
                        "acme.storage.bucket=b1",
                        "acme.storage.presign-ttl=5m",
                        "acme.storage.url-mode=PUBLIC")
                .run(context -> {
                    StorageProperties properties = context.getBean(StorageProperties.class);
                    assertThat(properties.getType()).isEqualTo(StorageProperties.Type.OSS);
                    assertThat(properties.getBucket()).isEqualTo("b1");
                    assertThat(properties.getUrlMode()).isEqualTo(StorageProperties.UrlMode.PUBLIC);
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(OssObjectStorage.class);
                });
    }

    @Test
    void missingCredentialsFailsExplicitlyForRemoteAdapters() {
        // SYS-15-0003：minio/oss 凭证缺失显式失败（local 不适用）
        runner.withPropertyValues("acme.storage.type=minio").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOfSatisfying(BizException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(StorageError.CREDENTIAL_MISSING));
        });
    }

    @Test
    void missingLocalRootDirFailsExplicitly() {
        // SYS-15-0006：type=local 根目录缺失显式失败（不静默空转）
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOfSatisfying(BizException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(StorageError.LOCAL_ROOT_DIR_MISSING));
        });
    }
}
