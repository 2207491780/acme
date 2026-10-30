package com.acme.framework.security.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.security.internal.DefaultInternalTokenVerifier;
import com.acme.framework.security.internal.JwsInternalTokenIssuer;
import com.acme.framework.security.interceptor.DataPermissionAspect;
import com.acme.framework.security.interceptor.InternalTokenAuthFilter;
import com.acme.framework.security.support.DataPermissionEvaluator;
import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.security.support.InternalTokenVerifier;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * framework-security 总装（FR-026 开关装配），经 AutoConfiguration.imports 注册（Modules.md 3.5）。
 * Servlet 过滤器经内嵌条件配置类装配（framework-trace HttpChannelConfig 同型，7.0）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("security")
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** 内部令牌校验（FR-014；业务可注册 InternalTokenVerifier 覆盖，7.7 换算法仅换 internal 实现）。 */
    @Bean
    @ConditionalOnMissingBean(InternalTokenVerifier.class)
    public InternalTokenVerifier internalTokenVerifier(SecurityProperties properties) {
        return new DefaultInternalTokenVerifier(properties.getToken());
    }

    /** 内部令牌签发（Q-D001 归位：与校验同居，gateway/feign 经该接缝签发）。 */
    @Bean
    @ConditionalOnMissingBean(InternalTokenIssuer.class)
    public InternalTokenIssuer internalTokenIssuer(SecurityProperties properties) {
        return new JwsInternalTokenIssuer(properties.getToken());
    }

    /** 数据权限拦截切面（FR-017、Q-D09）。 */
    @Bean
    @ConditionalOnProperty(prefix = "acme.security.data-permission", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public DataPermissionAspect dataPermissionAspect(ObjectProvider<DataPermissionEvaluator> evaluators) {
        return new DataPermissionAspect(evaluators);
    }

    /** Servlet 通道装配（FR-014~018）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletChannelConfig {

        @Bean
        public InternalTokenAuthFilter internalTokenAuthFilter(InternalTokenVerifier verifier,
                                                               SecurityProperties properties) {
            return new InternalTokenAuthFilter(verifier, properties.getPublicPaths());
        }
    }
}
