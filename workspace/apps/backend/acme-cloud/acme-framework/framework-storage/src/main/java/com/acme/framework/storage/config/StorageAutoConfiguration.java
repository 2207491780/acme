package com.acme.framework.storage.config;

import com.acme.common.core.BizException;
import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
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
 * {@code type=minio|oss} 选择适配器（FR-012/013）；凭证配置缺失显式失败（SYS-15-0003，
 * 架构设计 9.2 不静默空转）；ObjectStorage 可注册自定义实现覆盖。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("storage")
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 对象存储门面（FR-011；双适配器按 type 选择）。 */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    public ObjectStorage objectStorage(StorageProperties properties) {
        requireConfig(properties);
        return properties.getType() == StorageProperties.Type.OSS
                ? OssObjectStorage.connect(properties)
                : MinioObjectStorage.connect(properties);
    }

    /** 凭证配置缺失显式失败（SYS-15-0003，架构设计 9.2）。 */
    private static void requireConfig(StorageProperties properties) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()
                || properties.getAccessKey() == null || properties.getAccessKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            throw new BizException(StorageError.CREDENTIAL_MISSING);
        }
    }
}
