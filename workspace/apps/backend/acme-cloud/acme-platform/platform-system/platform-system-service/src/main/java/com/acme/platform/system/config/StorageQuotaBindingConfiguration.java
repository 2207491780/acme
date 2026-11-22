package com.acme.platform.system.config;

import com.acme.framework.storage.config.StorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 租户配额平台缺省绑定（REQ-20261122-150802 确认③）：platform-system 仅绑定 framework-storage
 * {@link StorageProperties} 读平台缺省配额值（acme.storage.quota.default-bytes，缺省 1GiB、0=不限，
 * 与 platform-infra 文件域同前缀同键单源）；存储适配器（LocalObjectStorage 等）归 platform-infra
 * 文件域——本服务主配置 acme.storage.enabled=false 关闭 StorageAutoConfiguration 装配面
 * （分布式 platform-system 无根目录/桶面），仅保留配置绑定（模块关闭后自动装配不注册属性 bean，
 * 由本配置类承载；单体形态 storage 模块开启时属性注册幂等，行为不变）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageQuotaBindingConfiguration {
}
