package com.acme.platform.infra.infrastructure.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 直传一次性票据存储（REQ-20261117-045754 确认①⑥，参照 framework-stream RedisDedupStore 风格）：
 * 票据 = UUID，值 = 票据元数据 JSON，Redis SET + TTL 5min；取票 Lua GET+DEL 原子（单次消费语义——
 * 不存在/过期/已消费统一返回空）。键带服务语义前缀（12.3.2）：{@code infra:file:upload-ticket:{ticket}}；
 * 票据绑定租户与操作者（防跨租户替传，校验在用例层执行）。
 */
@Component
public class UploadTicketStore {

    /** 票据键前缀（{ticket} 为 UUID）。 */
    public static final String KEY_PREFIX = "infra:file:upload-ticket:";

    /** 缺省 TTL（确认①：5min）。 */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /** 取票：GET + DEL 原子（Lua 单次消费，不依赖 GETDEL 服务端版本）。 */
    private static final RedisScript<String> TAKE = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if v then redis.call('DEL', KEYS[1]) end
            return v
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public UploadTicketStore(StringRedisTemplate redisTemplate, JsonMapper jsonMapper) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    /** 写入票据元数据并返回票据 id（UUID）。 */
    public String create(Ticket ticket, Duration ttl) {
        String ticketId = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(KEY_PREFIX + ticketId,
                jsonMapper.writeValueAsString(ticket),
                ttl == null ? DEFAULT_TTL : Duration.ofSeconds(Math.max(ttl.getSeconds(), 1)));
        return ticketId;
    }

    /** 取票（原子消费）：不存在/过期/已消费统一空；值损坏亦按空处理（票据面不外泄内部异常）。 */
    public Optional<Ticket> take(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        String json = redisTemplate.execute(TAKE, List.of(KEY_PREFIX + ticketId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(json, Ticket.class));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * 票据元数据（冻结 preparation 时点）：落对象键（带租户段）/原名/类型/大小/业务标注 +
     * 租户与操作者绑定（防跨租户替传，确认⑥）。
     */
    public record Ticket(String objectKey, String fileName, String contentType, long size,
                         String bizType, String bizId, String tenantId, String operatorId) {
    }
}
