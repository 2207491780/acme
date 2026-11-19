package com.acme.platform.system.notify;

import com.acme.platform.system.domain.SysPushToken;
import com.acme.platform.system.infrastructure.repository.SysPushTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Expo APP 推送发送器（REQ-20261119-082531 确认记录⑥）：站内信新消息触达语义——
 * 对目标用户注册的全部推送令牌逐个 POST Expo v2 发送端点，载荷
 * {@code {to, title, body, priority=default}}（body=标题同文，站内信无正文摘要字段）。
 * fire-and-forget：@Async 异步（applicationTaskExecutor）、单令牌失败仅 WARN 不外抛、
 * 不重试（失败令牌由下次消息触达自然覆盖）；无令牌用户零行为（不外呼）。
 * bean 仅在 {@code acme.notify.push.enabled=true} 时装配（关闭态零行为，监听器旁路无操作）。
 */
@Component
@ConditionalOnProperty(name = "acme.notify.push.enabled", havingValue = "true")
public class ExpoPushSender {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushSender.class);

    private final RestClient restClient;
    private final PushProperties properties;
    private final SysPushTokenRepository pushTokenRepository;

    public ExpoPushSender(RestClient expoPushRestClient, PushProperties properties,
                          SysPushTokenRepository pushTokenRepository) {
        this.restClient = expoPushRestClient;
        this.properties = properties;
        this.pushTokenRepository = pushTokenRepository;
    }

    /**
     * 用户级发送入口（PushNotifyListener 事务提交后旁路触发）：查本人令牌逐个发送。
     * 失败仅 WARN 不外抛（BR-019 同款口径——异常不向发布方传播）。
     */
    @Async
    public void send(Long userId, String title) {
        if (!properties.isEnabled()) {
            return;
        }
        List<SysPushToken> tokens = pushTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            return;
        }
        for (SysPushToken token : tokens) {
            sendToken(token.getToken(), title);
        }
    }

    /** 单令牌发送（fire-and-forget：非 2xx / IO 异常 → WARN 不外抛）。 */
    void sendToken(String token, String title) {
        try {
            restClient.post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PushPayload(token, title))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.warn("Expo 推送发送失败（跳过，不重试）: token={}, error={}", token, ex.getMessage());
        }
    }

    /** Expo v2 载荷（确认记录⑥）：{to, title, body, priority=default}。 */
    record PushPayload(String to, String title, String body, String priority) {

        PushPayload(String to, String title) {
            this(to, title, title, "default");
        }
    }
}
