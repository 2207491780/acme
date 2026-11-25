package com.acme.platform.infra.internal.audit;

import com.acme.framework.tenant.support.CrossTenantAuditSink;
import com.acme.platform.infra.infrastructure.repository.CrossTenantAuditRowRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 跨租户审计 DB sink 装配（REQ-20261125-001736 AC-3）：注册 {@link CrossTenantAuditDbSink} 为
 * {@link CrossTenantAuditSink} bean（framework-tenant TenantAutoConfiguration 经 ObjectProvider
 * 接入 CrossTenantAuditLogger）；应用注册自己的 sink bean 时本装配退避。
 */
@Configuration(proxyBeanMethods = false)
public class CrossTenantAuditSinkConfig {

    @Bean
    @ConditionalOnMissingBean(CrossTenantAuditSink.class)
    public CrossTenantAuditDbSink crossTenantAuditDbSink(CrossTenantAuditRowRepository repository) {
        return new CrossTenantAuditDbSink(repository);
    }
}
