package com.acme.framework.workflow.config;

import com.acme.framework.security.support.InternalTokenIssuer;
import com.acme.framework.workflow.support.WorkflowServiceTaskCallbackClient;
import com.acme.framework.workflow.support.WorkflowServiceTaskHandler;
import com.acme.framework.workflow.support.WorkflowServiceTaskHandlerRegistry;
import com.acme.framework.workflow.support.WorkflowServiceTaskReceiveController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * framework-workflow 总装（REQ-20261122-233425 确认②；业务侧 pom 引入即装配），经
 * AutoConfiguration.imports 注册（Modules.md 3.5，framework-job 同款）：handler 注册表 +
 * ServiceTask 委托接收端点 + 引擎 internal callback 自动回调。
 *
 * <p>装配条件：{@code acme.workflow.service-task.enabled}（缺省开）；接收端点与回调客户端另需
 * servlet web + RestClient/内部令牌签发在场（framework-security/使用方 Web 运行时为 optional
 * 依赖——纯本地无 web 形态仅保留注册表语义）。workflow 寻址缺省经 LoadBalancer（使用方单体
 * 可显式 callback-base-url 直连本机聚合端点，确认⑥）。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "acme.workflow.service-task", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(WorkflowServiceTaskProperties.class)
public class WorkflowServiceTaskAutoConfiguration {

    /** handler 注册表（handler bean 经下方收集器在上下文刷新后登记——部署形态占位符解析可达）。 */
    @Bean
    public WorkflowServiceTaskHandlerRegistry workflowServiceTaskHandlerRegistry() {
        return new WorkflowServiceTaskHandlerRegistry();
    }

    /**
     * handler 收集器（REQ-20261122-233425 确认②①；framework-job TaskRegistryCollector 同款：
     * ApplicationListener 收集，不作 BeanPostProcessor——消除提前拉取装配 bean 的启动告警）。
     * 双事件二次登记：ContextRefreshedEvent（refresh 尾声）+ WebServerInitializedEvent
     * （web 服务器启动即发、先于 ContextRefreshedEvent——RANDOM_PORT 形态 local.server.port
     * 随该事件绑入环境，handler serviceRef 的部署形态占位符二登时点解析可达，消除
     * ContextRefreshedEvent 单点时序依赖）。子上下文（LB ClientFactory 每服务名一子上下文）
     * 刷新事件向父传播的重复登记由注册表同实例幂等消化（Registry putIfAbsent）。
     */
    @Bean
    public static org.springframework.context.ApplicationListener<org.springframework.context.event.ContextRefreshedEvent>
            serviceTaskHandlerCollector(org.springframework.beans.factory.ObjectProvider<WorkflowServiceTaskHandler> handlers,
                                        org.springframework.beans.factory.ObjectProvider<WorkflowServiceTaskHandlerRegistry> registry) {
        return event -> collectHandlers(handlers, registry);
    }

    /** handler 二次登记（WebServerInitializedEvent：local.server.port 绑定后换键重登——稳态面）。 */
    @Bean
    public static org.springframework.context.ApplicationListener<org.springframework.boot.web.server.context.WebServerInitializedEvent>
            serviceTaskHandlerReBinder(org.springframework.beans.factory.ObjectProvider<WorkflowServiceTaskHandler> handlers,
                                       org.springframework.beans.factory.ObjectProvider<WorkflowServiceTaskHandlerRegistry> registry) {
        return event -> collectHandlers(handlers, registry);
    }

    private static void collectHandlers(org.springframework.beans.factory.ObjectProvider<WorkflowServiceTaskHandler> handlers,
                                        org.springframework.beans.factory.ObjectProvider<WorkflowServiceTaskHandlerRegistry> registry) {
        WorkflowServiceTaskHandlerRegistry target = registry.getIfAvailable();
        if (target == null) {
            return;
        }
        for (WorkflowServiceTaskHandler handler : handlers) {
            target.register(handler);
        }
    }

    /** 引擎回调客户端（令牌签发缺席不装配——无 framework-security 形态无法完成回调闭环）。 */
    @Bean
    @ConditionalOnClass({RestClient.class, InternalTokenIssuer.class})
    public WorkflowServiceTaskCallbackClient workflowServiceTaskCallbackClient(
            ObjectProvider<InternalTokenIssuer> tokenIssuer, WorkflowServiceTaskProperties properties,
            ObjectProvider<LoadBalancerClient> loadBalancer, Environment environment) {
        return new WorkflowServiceTaskCallbackClient(tokenIssuer.getIfAvailable(), properties,
                loadBalancer.getIfAvailable(), environment);
    }

    /** 委托接收端点（servlet web + 回调客户端在场；Bean 注解经 spring-web，mvc 由使用方运行时携带）。 */
    @Bean
    @ConditionalOnClass({RestClient.class, InternalTokenIssuer.class})
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public WorkflowServiceTaskReceiveController workflowServiceTaskReceiveController(
            WorkflowServiceTaskHandlerRegistry registry, WorkflowServiceTaskCallbackClient callbackClient,
            WorkflowServiceTaskProperties properties) {
        return new WorkflowServiceTaskReceiveController(registry, callbackClient,
                properties.isReceiveAsync(), properties.getReceiveInitialDelayMs(),
                properties.getReceiveCallbackDelayMs());
    }
}
