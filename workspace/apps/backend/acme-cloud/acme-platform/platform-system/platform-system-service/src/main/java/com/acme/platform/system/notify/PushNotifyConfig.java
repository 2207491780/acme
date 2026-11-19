package com.acme.platform.system.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

/**
 * APP 推送装配（REQ-20261119-082531 确认记录⑥）：开关 {@code acme.notify.push.enabled=true} 时
 * 装配 Expo 发送端 RestClient bean（baseUrl=acme.notify.push.url，spring-web 7 内置、无新依赖）并
 * 启用 {@code @Async}（fire-and-forget 发送不阻塞站内信主路径，对齐 LoginLogRecorder/OpExceptionRecorder
 * 异步口径）；关闭态零行为——发送器 bean 与 RestClient bean 均不装配。
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(PushProperties.class)
public class PushNotifyConfig {

    /** Expo 发送端 RestClient（开关开启时装配；baseUrl 指向 acme.notify.push.url）。 */
    @Bean
    @ConditionalOnProperty(name = "acme.notify.push.enabled", havingValue = "true")
    public RestClient expoPushRestClient(PushProperties properties) {
        return RestClient.builder().baseUrl(properties.getUrl()).build();
    }
}
