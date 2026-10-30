package com.acme.framework.cache.interceptor;

import com.acme.framework.cache.internal.CaffeineL1Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * L1 失效广播（FR-006/AC-006，Q-D002 定稿 Redis Pub/Sub）：写/删后 publish 清各实例 L1；
 * 通道 {@code acme:cache:invalidate:{service}}（12.3.2 服务前缀）；广播失败兜底清本地 + L1 短 TTL 过期（6.3）。
 * 消息带来源实例 id，收到后跳过自身（防回环清掉本实例刚写入的 L1）。
 */
public class L1InvalidationBus implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(L1InvalidationBus.class);

    private static final char SOURCE_SEPARATOR = 1;

    private final RedisTemplate<String, String> redisTemplate;
    private final String channel;
    private final CaffeineL1Cache l1;
    private final String instanceId;

    public L1InvalidationBus(RedisTemplate<String, String> redisTemplate, String channel, CaffeineL1Cache l1) {
        this(redisTemplate, channel, l1, UUID.randomUUID().toString());
    }

    public L1InvalidationBus(RedisTemplate<String, String> redisTemplate, String channel, CaffeineL1Cache l1,
                             String instanceId) {
        this.redisTemplate = redisTemplate;
        this.channel = channel;
        this.l1 = l1;
        this.instanceId = instanceId == null ? "" : instanceId;
    }

    /** 广播通道名（12.3.2 服务前缀）。 */
    public static String channelOf(String servicePrefix) {
        return "acme:cache:invalidate:" + servicePrefix;
    }

    /** 本实例标识（消息来源标记，订阅侧跳过自身）。 */
    public String instanceId() {
        return instanceId;
    }

    /** 广播清除（本实例已在调用方本地清过 L1，订阅回调跳过自身；失败兜底清本地）。 */
    public void publishEvict(String key) {
        try {
            redisTemplate.convertAndSend(channel, instanceId + SOURCE_SEPARATOR + key);
        } catch (RuntimeException ex) {
            log.warn("缓存失效广播失败，本地兜底清除 + L1 TTL 兜底（FR-006）: {}", ex.getMessage());
            l1.invalidate(key);
        }
    }

    /** 订阅回调：非来源实例清除本实例 L1（跳过自身防回环）。 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        int sep = body.indexOf(SOURCE_SEPARATOR);
        String source = sep < 0 ? "" : body.substring(0, sep);
        String key = sep < 0 ? body : body.substring(sep + 1);
        if (instanceId.equals(source)) {
            log.debug("L1 失效跳过自身（FR-006）: {}", key);
            return;
        }
        l1.invalidate(key);
        log.debug("L1 失效已应用（FR-006）: {}", key);
    }
}
