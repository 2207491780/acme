package com.acme.framework.i18n.config;

import com.acme.common.i18n.MessageSource;
import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.core.support.PromptLocalizer;
import com.acme.framework.i18n.internal.MessageSourceAssembler;
import com.acme.framework.i18n.internal.PromptLocalizerImpl;
import com.acme.framework.i18n.interceptor.LocaleInterceptor;
import com.acme.framework.i18n.support.I18nLocaleResolver;
import com.acme.framework.i18n.support.MessageSourceAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * framework-i18n 总装（FR-024 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）；
 * Locale 解析唯一入口 + MessageSource 装配 + {@link PromptLocalizer} 实现（D-006 供 framework-web 可选接入）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("i18n")
@EnableConfigurationProperties(I18nProperties.class)
public class I18nAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** Locale 解析唯一入口（FR-006、BR-011）。 */
    @Bean
    @ConditionalOnMissingBean(I18nLocaleResolver.class)
    public I18nLocaleResolver i18nLocaleResolver(I18nProperties properties) {
        return new I18nLocaleResolver(properties.getDefaultLocale(), properties.getFallbackLocale());
    }

    /** MessageSource 适配（FR-007、DP-012）。 */
    @Bean
    @ConditionalOnMissingBean(MessageSource.class)
    public MessageSourceAdapter messageSourceAdapter(I18nProperties properties, I18nLocaleResolver resolver) {
        return new MessageSourceAdapter(MessageSourceAssembler.assemble(properties),
                resolver.fallbackLocale(), properties.getMissingKey());
    }

    /** 面向调用方提示的本地化出口（D-006，framework-web ObjectProvider 可选接入）。 */
    @Bean
    @ConditionalOnMissingBean(PromptLocalizer.class)
    public PromptLocalizer promptLocalizer(MessageSourceAdapter messageSourceAdapter) {
        return new PromptLocalizerImpl(messageSourceAdapter);
    }

    /** Locale 拦截（FR-006；Servlet 语境随 web）。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public LocaleInterceptor localeInterceptor(I18nLocaleResolver resolver, I18nProperties properties) {
        return new LocaleInterceptor(resolver, properties.getHeaderName(), properties.getOverrideHeader());
    }

    /** 拦截注册（请求结束 clear，2.4.3）。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public WebMvcConfigurer localeInterceptorConfigurer(LocaleInterceptor localeInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(localeInterceptor).addPathPatterns("/**");
            }
        };
    }
}
