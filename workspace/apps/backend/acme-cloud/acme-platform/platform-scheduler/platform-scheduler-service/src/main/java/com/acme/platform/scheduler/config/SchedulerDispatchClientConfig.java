package com.acme.platform.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 调度下发通道装配（REQ-20261121-071002 确认⑤；设计规格 §3.2/§4 终稿）：
 * <ul>
 *   <li>下发 RestClient：plain builder + 独立请求工厂（连接 5s / 读超时=接单超时 10s）；
 *       实例寻址经 LoadBalancerClient.choose(app_name) 直解（框架既有服务间调用惯例为 Feign
 *       静态服务名，下发目标 app_name 是定义级动态值——RestClient+@LoadBalanced 需自建
 *       {@code @LoadBalanced RestClient.Builder} bean 且拦截器装配依赖 BPP 时序，选型
 *       LoadBalancerClient 直解更贴合动态寻址语义，报告已注明）；</li>
 *   <li>下发线程池：独立于 tick 线程（执行下发为 IO 密集，吞吐随实例数横向增长）。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SchedulerDispatchClientConfig {

    /** 连接建立超时（接单超时的连接段；确认⑤读超时 10s 为接单总口径）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 下发 RestClient（独立请求工厂承载接单超时；实例寻址由 JobDispatcher 经 LB 直解）。 */
    @Bean
    public RestClient schedulerDispatchRestClient(SchedulerProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getDispatch().getTimeoutMs()));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 下发线程池（IO 密集独立于 tick；队列满时提交失败按 DISPATCH_FAILED 走重试）。 */
    @Bean
    public ThreadPoolTaskExecutor schedulerDispatchExecutor(SchedulerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sch-dispatch-");
        executor.setCorePoolSize(properties.getDispatch().getPoolSize());
        executor.setMaxPoolSize(properties.getDispatch().getPoolSize());
        executor.setQueueCapacity(properties.getDispatch().getQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAcceptTasksAfterContextClose(false);
        return executor;
    }
}
