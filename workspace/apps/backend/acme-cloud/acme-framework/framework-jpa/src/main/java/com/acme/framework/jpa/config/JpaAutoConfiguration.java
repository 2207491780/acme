package com.acme.framework.jpa.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.jpa.internal.DefaultEntityQueries;
import com.acme.framework.jpa.internal.QuerydslTranslator;
import com.acme.framework.jpa.internal.SlowSqlSupport;
import com.acme.framework.jpa.interceptor.DefaultAuditor;
import com.acme.framework.jpa.support.Auditor;
import com.acme.framework.jpa.support.BatchWriter;
import com.acme.framework.jpa.support.EntityQueries;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * framework-jpa 总装（FR-026 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * QueryDSL 装配 bean 收敛 internal、不对使用方暴露（6.5、BR-021）；不建业务表（BR-022）。
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnAcmeModule("jpa")
@EnableConfigurationProperties(JpaProperties.class)
public class JpaAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 操作人解析（FR-008、6.2；业务可注册 Auditor 覆盖）。 */
    @Bean
    @ConditionalOnMissingBean(Auditor.class)
    @ConditionalOnProperty(prefix = "acme.jpa.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Auditor defaultAuditor(JpaProperties properties) {
        return new DefaultAuditor(properties);
    }

    /** JPA 基础设施就绪后装配（QueryDSL 装配与批量写入，6.5/6.6）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(EntityManagerFactory.class)
    static class JpaInfrastructureConfig {

        /** QueryDSL 装配（6.5；bean 收敛 internal 使用面，使用方经 EntityQueries）。 */
        @Bean
        public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
            return new JPAQueryFactory(entityManager);
        }

        /** 查询封装面（6.5 使用方唯一入口）。 */
        @Bean
        public EntityQueries entityQueries(JPAQueryFactory queryFactory) {
            return new DefaultEntityQueries(new QuerydslTranslator(queryFactory));
        }

        /** 批量写入（FR-012）。 */
        @Bean
        public BatchWriter batchWriter(EntityManager entityManager, JpaProperties properties) {
            return new BatchWriter(entityManager, properties);
        }
    }

    /** 慢 SQL 采集（FR-013、6.7）：包装 DataSource，超阈值 WARN。 */
    @Bean
    @ConditionalOnProperty(prefix = "acme.jpa.slow-sql", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static BeanPostProcessor slowSqlDataSourceWrapper(JpaProperties properties) {
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
