package com.acme.framework.flex.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.flex.interceptor.DefaultAuditor;
import com.acme.framework.flex.internal.SlowSqlSupport;
import com.acme.framework.flex.support.AuditBase;
import com.acme.framework.flex.support.AuditFillListener;
import com.acme.framework.flex.support.Auditor;
import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.logicdelete.impl.BooleanLogicDeleteProcessor;
import com.mybatisflex.core.logicdelete.LogicDeleteProcessor;
import com.mybatisflex.spring.boot.MyBatisFlexCustomizer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * framework-mybatis-flex 总装（FR-026 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * 装配收敛 internal、不对使用方暴露（6.5、BR-021）；不建业务表（BR-022）。
 *
 * <p>审计监听器经官方 {@link MyBatisFlexCustomizer} 钩子注册到 {@link FlexGlobalConfig}
 * （starter 逐一回调所有 customizer，传入其运行期使用的缺省全局配置实例），
 * 注册在基类 {@link AuditBase} 上按 isAssignableFrom 匹配覆盖所有子类实体。</p>
 */
@AutoConfiguration(after = com.mybatisflex.spring.boot.v4.MybatisFlexAutoConfiguration.class)
@ConditionalOnAcmeModule("flex")
@EnableConfigurationProperties(FlexProperties.class)
public class FlexAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 操作人解析（FR-008、6.2；业务可注册 Auditor 覆盖）。 */
    @Bean
    @ConditionalOnMissingBean(Auditor.class)
    @ConditionalOnProperty(prefix = "acme.flex.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Auditor defaultAuditor(FlexProperties properties) {
        return new DefaultAuditor(properties);
    }

    /** Boolean 逻辑删除处理器（AuditBase.deleted 为 Boolean；缺省处理器为 int 0/1，类型不匹配必须替换）。 */
    @Bean
    @ConditionalOnMissingBean(LogicDeleteProcessor.class)
    public LogicDeleteProcessor booleanLogicDeleteProcessor() {
        return new BooleanLogicDeleteProcessor();
    }

    /** 审计填充监听器全局注册（12.3.1、6.2；audit.enabled=false 时不注册，行为与未引入一致）。 */
    @Bean
    public MyBatisFlexCustomizer auditFillCustomizer(FlexProperties properties) {
        return config -> {
            if (properties.getAudit().isEnabled()) {
                AuditFillListener listener = new AuditFillListener();
                config.registerInsertListener(listener, AuditBase.class);
                config.registerUpdateListener(listener, AuditBase.class);
            }
        };
    }

    /** 慢 SQL 采集（FR-013、6.7）：包装 DataSource，超阈值 WARN。 */
    @Bean
    @ConditionalOnProperty(prefix = "acme.flex.slow-sql", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static BeanPostProcessor slowSqlDataSourceWrapper(FlexProperties properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource dataSource && !(bean instanceof SlowSqlSupport.Proxied)) {
                    return SlowSqlSupport.wrap(dataSource, properties);
                }
                return bean;
            }
        };
    }
}
