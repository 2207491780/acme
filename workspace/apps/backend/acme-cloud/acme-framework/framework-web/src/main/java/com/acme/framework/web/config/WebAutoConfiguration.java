package com.acme.framework.web.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.web.interceptor.AccessLogFilter;
import com.acme.framework.web.interceptor.ClientContextExtractFilter;
import com.acme.framework.web.interceptor.GlobalExceptionTranslator;
import com.acme.framework.web.interceptor.StandardResponseAdvice;
import com.acme.framework.web.support.ClientContextParser;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * framework-web 总装（FR-026 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）。
 * Servlet 形态过滤器经内嵌条件配置类装配（framework-trace HttpChannelConfig 同型）；无 CORS（BR-011）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("web")
@EnableConfigurationProperties(WebProperties.class)
public class WebAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 统一出参包装（FR-003）。 */
    @Bean
    public StandardResponseAdvice standardResponseAdvice(ObjectProvider<JsonMapper> jsonMapper) {
        return new StandardResponseAdvice(jsonMapper.getIfAvailable());
    }

    /** 全局异常映射（FR-004/005；D-006 PromptLocalizer 可选接入）。 */
    @Bean
    public GlobalExceptionTranslator globalExceptionTranslator(ObjectProvider<PromptLocalizer> promptLocalizers) {
        return new GlobalExceptionTranslator(promptLocalizers);
    }

    /** ClientContext 解析器（FR-007）。 */
    @Bean
    public ClientContextParser clientContextParser(WebProperties properties) {
        return new ClientContextParser(properties);
    }

    /** Servlet 通道装配（FR-006/007）：Filter bean 由 Boot 自动注册。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletChannelConfig {

        @Bean
        @ConditionalOnProperty(prefix = "acme.web.access-log", name = "enabled", havingValue = "true",
                matchIfMissing = true)
        public AccessLogFilter accessLogFilter(Environment environment) {
            return new AccessLogFilter(environment.getProperty("spring.application.name", "application"));
        }

        @Bean
        public ClientContextExtractFilter clientContextExtractFilter(ClientContextParser parser) {
            return new ClientContextExtractFilter(parser);
        }
    }
}
