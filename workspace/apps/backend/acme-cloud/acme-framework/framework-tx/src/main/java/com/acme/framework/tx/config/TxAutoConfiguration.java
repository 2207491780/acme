package com.acme.framework.tx.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.tx.internal.SeataAssemblyValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * framework-tx 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 仅装配面（Q-003）：不起 Seata Server、不跑运行实证；
 * 两级开关（取舍 #17）：{@code acme.tx.enabled} 模块开关 + {@code acme.tx.global.enabled} 默认 false。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("tx")
@EnableConfigurationProperties(TxProperties.class)
public class TxAutoConfiguration extends AcmeModuleAutoConfiguration {

    /**
     * Seata 装配校验（FR-021、架构设计 13.3）：仅显式开启全局开关时接线，
     * 校验失败启动期显式失败（SYS-19-0001/0002）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "acme.tx.global", name = "enabled", havingValue = "true")
    public SeataAssemblyValidator seataAssemblyValidator(TxProperties properties) {
        return new SeataAssemblyValidator(properties);
    }
}
