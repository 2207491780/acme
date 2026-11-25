package com.acme.framework.tenant.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.tenant.internal.FlexTenantFactory;
import com.acme.framework.tenant.internal.TenantWriteGuard;
import com.acme.framework.tenant.interceptor.IgnoreTenantAspect;
import com.acme.framework.tenant.support.CrossTenantAuditLogger;
import com.acme.framework.tenant.support.CrossTenantAuditSink;
import com.mybatisflex.spring.boot.MyBatisFlexCustomizer;
import org.springframework.beans.factory.ObjectProvider;
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

    /** MyBatis-Flex 租户标识工厂（FR-001/002；starter 自动接线 TenantManager）。 */
    @Bean
    @ConditionalOnMissingBean(com.mybatisflex.core.tenant.TenantFactory.class)
    public FlexTenantFactory flexTenantFactory() {
        return new FlexTenantFactory();
    }

    /** 未绑定租户写入拒绝（fail-closed 数据操作面落点）：注册为全局 Insert 监听器（覆盖全部实体）。 */
    @Bean
    public MyBatisFlexCustomizer tenantWriteGuardCustomizer(TenantWriteGuard guard) {
        return config -> config.registerInsertListener(guard, Object.class);
    }

    @Bean
    public TenantWriteGuard tenantWriteGuard() {
        return new TenantWriteGuard();
    }

    /** 跨租户审计（FR-004，Q-005 修订：日志兑底 + 可选 sink 尽力落库，AC-2/AC-3；无 sink 仅日志不报错）。 */
    @Bean
    public CrossTenantAuditLogger crossTenantAuditLogger(ObjectProvider<CrossTenantAuditSink> sinks) {
        return new CrossTenantAuditLogger(sinks.getIfAvailable());
    }

    /** 忽略注解切面（FR-003/004）。 */
    @Bean
    public IgnoreTenantAspect ignoreTenantAspect(CrossTenantAuditLogger auditLogger, TenantProperties properties) {
        return new IgnoreTenantAspect(auditLogger, properties);
    }
}
