package com.acme.framework.jackson.config;

import com.acme.common.serialization.Json;
import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.jackson.internal.AcmeJsonMapperCustomizer;
import com.acme.framework.jackson.support.AcmeJacksonComponent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;

/**
 * framework-jackson 总装（FR-010 开关装配，架构设计 4.5），经 AutoConfiguration.imports 注册（BR-007）。
 * 复用 framework-core 自动装配基类与开关条件（Q-002、BR-021）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("jackson")
@EnableConfigurationProperties(JacksonProperties.class)
public class JacksonAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** JsonMapper 装配点（FR-007/FR-008）：标准 Module 集 + {@code @AcmeJacksonComponent} 组件。 */
    @Bean
    public JsonMapperBuilderCustomizer acmeJsonMapperCustomizer(ApplicationContext context) {
        Collection<Object> components = context.getBeansWithAnnotation(AcmeJacksonComponent.class).values();
        return new AcmeJsonMapperCustomizer(components);
    }

    /** Json 门面单点供给（2.5.2：全链路共用注入的 JsonMapper，P4 stream/cache 复用）。 */
    @Bean
    @ConditionalOnMissingBean(Json.class)
    public Json acmeJson(JsonMapper jsonMapper) {
        return new Json(jsonMapper);
    }
}
