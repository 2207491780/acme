package com.acme.framework.audit.config;

import com.acme.framework.audit.aspect.AuditAspect;
import com.acme.framework.audit.internal.SpringAuditEventPublisher;
import com.acme.framework.audit.support.AuditEventPublisher;
import com.acme.framework.audit.support.AuditEventSink;
import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * framework-audit 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 本期仅埋点 + 事件出口（Q-005：落库与查询归 platform-infra 后续特性，不依赖 stream/jpa）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("audit")
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 审计事件出口（FR-009；默认 Spring 事件广播 + Sink 转发，可替换）。 */
    @Bean
    @ConditionalOnMissingBean(AuditEventPublisher.class)
    public AuditEventPublisher auditEventPublisher(ApplicationEventPublisher publisher,
                                                   ObjectProvider<AuditEventSink> sinks) {
        return new SpringAuditEventPublisher(publisher, sinks.orderedStream().toList());
    }

    /** 审计埋点切面（FR-008）。 */
    @Bean
    public AuditAspect auditAspect(AuditEventPublisher publisher, AuditProperties properties) {
        return new AuditAspect(publisher, properties);
    }
}
