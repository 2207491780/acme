package com.acme.framework.tenant.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.tenant.internal.HibernateTenantBridge;
import com.acme.framework.tenant.interceptor.IgnoreTenantAspect;
import com.acme.framework.tenant.support.CrossTenantAuditLogger;
import com.acme.framework.tenant.support.TenantIdentifierResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * framework-tenant 总装（FR-030 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 只读消费 TenantContext，不写入上下文（AC-001、Modules.md 2.4.4（v1.6）写入源口径）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("tenant")
@EnableConfigurationProperties(TenantProperties.class)
public class TenantAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 租户标识解析器（FR-001/002）。 */
    @Bean
    @ConditionalOnMissingBean(TenantIdentifierResolver.class)
    public TenantIdentifierResolver tenantIdentifierResolver() {
        return new TenantIdentifierResolver();
    }

    /** Hibernate 装配适配（FR-002：@TenantId 自动过滤接线）。 */
    @Bean
    public HibernateTenantBridge hibernateTenantBridge(TenantIdentifierResolver resolver) {
        return new HibernateTenantBridge(resolver);
    }

    /** 跨租户审计（FR-004，Q-005 结构化日志）。 */
    @Bean
    public CrossTenantAuditLogger crossTenantAuditLogger() {
        return new CrossTenantAuditLogger();
    }

    /** 忽略注解切面（FR-003/004）。 */
    @Bean
    public IgnoreTenantAspect ignoreTenantAspect(CrossTenantAuditLogger auditLogger, TenantProperties properties) {
        return new IgnoreTenantAspect(auditLogger, properties);
    }
}
