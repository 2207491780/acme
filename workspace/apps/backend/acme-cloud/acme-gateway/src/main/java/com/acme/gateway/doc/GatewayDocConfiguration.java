package com.acme.gateway.doc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聚合 API 文档装配（REQ-20261114-120106）：{@code acme.doc.enabled} 单一配置源；
 * 路由（/v3/api-docs/{auth|system|infra}）与 UI（/docs）经 application.yml + springdoc 装配，
 * 本类仅供给开关绑定与关闭守卫（/docs 端点的关闭由 springdoc.swagger-ui.enabled 占位符绑定同一开关）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayDocProperties.class)
public class GatewayDocConfiguration {

    @Bean
    public DocGuardFilter docGuardFilter(GatewayDocProperties properties) {
        return new DocGuardFilter(properties);
    }
}
