package com.acme.platform.workflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * ServiceTask 委托下发通道装配（确认⑤/设计规格 §7）：plain RestClient + 独立请求工厂
 * （连接 5s / 读超时=委托缺省超时 10s，acme:timeoutMs 节点级覆盖由下发侧按请求级
 * requestFactory 不可变——以整体缺省承载，节点级超时经 payload 透传由目标服务侧裁决）；
 * 实例寻址经 LoadBalancerClient.choose(serviceRef) 直解（SchedulerDispatchClientConfig 同款
 * 选型：动态服务名经 LB 直解更贴合，报告已注明先例）。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowDelegateClientConfig {

    /** 连接建立超时（委托下发连接段）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 委托下发 RestClient（独立请求工厂承载缺省读超时；服务名寻址由下发侧经 LB 直解）。 */
    @Bean
    public RestClient workflowDelegateRestClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(10_000L));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
