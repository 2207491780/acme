package com.acme.framework.storage.config;

import com.acme.common.core.BizException;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageError;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块开关与配置缺省测试（FR-024/AC-024、BR-003/004；凭证缺失显式失败 SYS-15-0003）。
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
    void zeroConfigBindsDefaults() {
        runner.withPropertyValues(
                        "acme.storage.endpoint=http://127.0.0.1:9000",
                        "acme.storage.access-key=k",
                        "acme.storage.secret-key=s")
                .run(context -> {
                    StorageProperties properties = context.getBean(StorageProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getType()).isEqualTo(StorageProperties.Type.MINIO);
                    assertThat(properties.getBucket()).isEqualTo("acme");
                    assertThat(properties.getPresignTtl()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                });
    }

    @Test
    void missingCredentialsFailsExplicitly() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOfSatisfying(BizException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(StorageError.CREDENTIAL_MISSING));
        });
    }

    @Test
    void customConfigBinds() {
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
                });
    }
}
