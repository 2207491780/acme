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
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.expression.BeanResolver;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * framework-audit 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 本期仅埋点 + 事件出口（Q-005：落库与查询归 platform-infra 后续特性，不依赖 stream/数据访问）。
 * REQ-20261117-061017（确认③）：切面增配 BeanResolver（beforeQuery {@code @beanName} 引用）
 * 与可选 JsonMapper（before 快照 JSON 序列化，缺省 toString 回退）。
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

    /** 审计埋点切面（FR-008；beforeQuery 旧值快照经 beanResolver + 可选 Jackson 支持）。 */
    @Bean
    public AuditAspect auditAspect(AuditEventPublisher publisher, AuditProperties properties,
                                   ApplicationContext applicationContext,
                                   ObjectProvider<JsonMapper> jsonMappers) {
        // Spring Framework 7 无 BeanFactoryResolver（BeanResolver 为函数式接口）：经 ApplicationContext 直查
        BeanResolver beanResolver = (context, beanName) -> applicationContext.getBean(beanName);
        return new AuditAspect(publisher, properties, beanResolver, jsonMappers.getIfAvailable());
    }
}
