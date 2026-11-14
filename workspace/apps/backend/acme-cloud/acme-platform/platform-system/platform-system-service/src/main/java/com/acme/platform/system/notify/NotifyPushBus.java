package com.acme.platform.system.notify;

import com.acme.common.serialization.Json;
import com.acme.framework.core.support.AfterCommitEventListener;
import com.acme.framework.core.support.KeyComposer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 站内信推送总线（REQ-20261114-120102 多实例扇出，模式照 framework-cache L1InvalidationBus 先例）：
 * 事务提交后事件（framework-core AfterCommitEventListener 惯例）→ Redis pub/sub 通道
 * {@code platform-system:notify:ws}（12.3.2 服务前缀，KeyComposer 拼接）→ 各实例订阅回调把帧投递给
 * 本地持有该 userId 的会话；发布实例自身同样经订阅回环收到（不跳过自身，与会话注册表语义一致）。
 * Redis 不可用时降级仅本实例投递（fail-open，可用性优先），订阅建立防御化（无 Redis 环境不阻断启动）。
 */
@Component
public class NotifyPushBus implements AfterCommitEventListener<NotifyPushEvent>, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(NotifyPushBus.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final KeyComposer keyComposer;
    private final Json json;
    private final WsSessionRegistry registry;

    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private volatile RedisMessageListenerContainer container;

    public NotifyPushBus(StringRedisTemplate redisTemplate, RedisConnectionFactory connectionFactory,
                         KeyComposer keyComposer, ObjectProvider<Json> jsons,
                         ObjectProvider<JsonMapper> mappers, WsSessionRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.keyComposer = keyComposer;
        this.json = resolveJson(jsons, mappers);
        this.registry = registry;
    }

    /** 广播通道名（12.3.2 服务前缀）。 */
    public String channel() {
        return keyComposer.compose("notify", "ws");
    }

    /** 启动即尝试建立订阅（无 Redis 环境捕获失败不阻断启动，后续事件再重试）。 */
    @PostConstruct
    void subscribeAtStartup() {
        ensureSubscribed();
    }

    /** 事务提交后回调（BR-013 提交后投递；BR-019 异常不向发布方传播，dispatcher 兜底）。 */
    @Override
    public void onEvent(NotifyPushEvent event) {
        if (event == null || event.recipientId() == null) {
            return;
        }
        // 载荷形态确认记录⑨：{userId, messageId, title}；帧（message:new）由订阅侧投递时组装
        String payload = json.toJson(new NotifyPushMessage(event.recipientId(), event.messageId(), event.title()));
        ensureSubscribed();
        try {
            redisTemplate.convertAndSend(channel(), payload);
        } catch (RuntimeException ex) {
            // Redis 不可用降级：仅本实例投递（fail-open，多实例其余实例由其各自订阅重试面兜底）
            log.warn("推送事件 Redis 广播失败，降级本实例投递（BR-019）: {}", ex.getMessage());
            deliverLocally(payload);
        }
    }

    /** 订阅回调：各实例把 message:new 帧投递给本地持有该 userId 的会话（含发布实例自身回环）。 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        deliverLocally(payload);
    }

    private void deliverLocally(String payload) {
        try {
            NotifyPushMessage message = json.fromJson(payload, NotifyPushMessage.class);
            registry.push(String.valueOf(message.userId()),
                    json.toJson(NotifyFrame.messageNew(message.messageId(), message.title())));
        } catch (RuntimeException ex) {
            log.warn("推送载荷投递失败（跳过）: {}", ex.getMessage());
        }
    }

    /**
     * 订阅建立（幂等防御化）：@PostConstruct 与首次发布事件双触发；无 Redis 环境（切片测试）捕获
     * 失败不阻断启动，后续事件再重试（Redis 恢复后自动接上）。
     */
    private synchronized void ensureSubscribed() {
        if (subscribed.get()) {
            return;
        }
        try {
            RedisMessageListenerContainer created = new RedisMessageListenerContainer();
            created.setConnectionFactory(connectionFactory);
            created.addMessageListener(this, new ChannelTopic(channel()));
            created.afterPropertiesSet();
            created.start();
            container = created;
            subscribed.set(true);
            log.info("站内信推送扇出订阅已建立: channel={}", channel());
        } catch (RuntimeException ex) {
            log.warn("站内信推送扇出订阅未建立（Redis 不可用，仅本实例投递）: {}", ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        RedisMessageListenerContainer current = container;
        if (current != null) {
            try {
                current.destroy();
            } catch (Exception ex) {
                log.debug("推送订阅容器销毁异常（忽略）: {}", ex.getMessage());
            }
        }
    }

    /** 共用 Json 门面（2.5.1 同 CacheAutoConfiguration 口径）：优先全局 Json bean，其次包装 JsonMapper。 */
    private static Json resolveJson(ObjectProvider<Json> jsons, ObjectProvider<JsonMapper> mappers) {
        Json existing = jsons.getIfAvailable();
        if (existing != null) {
            return existing;
        }
        JsonMapper mapper = mappers.getIfAvailable();
        if (mapper == null) {
            throw new IllegalStateException("缺少 Json/JsonMapper bean：请引入 framework-jackson 或声明 JsonMapper（2.5.1）");
        }
        return new Json(mapper);
    }
}
