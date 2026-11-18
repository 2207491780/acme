package com.acme.framework.trace.config;

import com.acme.framework.core.config.AcmeModuleAutoConfiguration;
import com.acme.framework.core.config.ConditionalOnAcmeModule;
import com.acme.framework.trace.interceptor.TraceFeignRequestInterceptor;
import com.acme.framework.trace.interceptor.TraceHttpFilter;
import com.acme.framework.trace.interceptor.TraceMessageSendInterceptor;
import feign.RequestInterceptor;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * framework-trace 总装（FR-006 开关装配，架构设计 3.8），经 AutoConfiguration.imports 注册（BR-007）。
 * 三通道内嵌条件配置类隔离 optional 三方接口的类加载（架构设计 3.8 类加载防护）。
 */
@AutoConfiguration
@ConditionalOnAcmeModule("trace")
@EnableConfigurationProperties(TraceProperties.class)
public class TraceAutoConfiguration extends AcmeModuleAutoConfiguration {

    /** HTTP 通道装配（FR-002）：Servlet Filter bean 由 Boot 自动注册（架构设计 3.3）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class HttpChannelConfig {

        @Bean
        TraceHttpFilter traceHttpFilter() {
            return new TraceHttpFilter();
        }
    }

    /** Feign 通道装配（FR-003）：feign 栈上自动生效（架构设计 3.4）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RequestInterceptor.class)
    static class FeignChannelConfig {

        @Bean
        TraceFeignRequestInterceptor traceFeignRequestInterceptor() {
            return new TraceFeignRequestInterceptor();
        }
    }

    /** Stream 发送侧装配（FR-004）；消费侧接线归 framework-stream P4（Q-003）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ChannelInterceptor.class)
    static class StreamChannelConfig {

        @Bean
        TraceMessageSendInterceptor traceMessageSendInterceptor() {
            return new TraceMessageSendInterceptor();
        }
    }
}
