package com.acme.framework.storage.config;

import com.acme.common.core.BizException;
import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.storage.internal.LocalObjectStorage;
import com.acme.framework.storage.internal.MinioObjectStorage;
import com.acme.framework.storage.internal.OssObjectStorage;
import com.acme.framework.storage.support.ObjectStorage;
import com.acme.framework.storage.support.StorageError;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * framework-storage 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * {@code type=local|minio|oss} 选择适配器（FR-012/013，local 缺省 REQ-20261114-071607）；
 * minio/oss 凭证配置缺失显式失败（SYS-15-0003，架构设计 9.2 不静默空转）；
 * ObjectStorage 可注册自定义实现覆盖。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("storage")
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 对象存储门面（FR-011；三适配器按 type 选择，local 缺省）。 */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    public ObjectStorage objectStorage(StorageProperties properties) {
        return switch (properties.getType()) {
            case LOCAL -> LocalObjectStorage.connect(properties);
            case MINIO -> {
                requireCredentials(properties);
                yield MinioObjectStorage.connect(properties);
            }
            case OSS -> {
                requireCredentials(properties);
                yield OssObjectStorage.connect(properties);
            }
        };
    }

    /** minio/oss 凭证配置缺失显式失败（SYS-15-0003，架构设计 9.2；local 无凭证诉求）。 */
    private static void requireCredentials(StorageProperties properties) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()
                || properties.getAccessKey() == null || properties.getAccessKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            throw new BizException(StorageError.CREDENTIAL_MISSING);
        }
    }
}
