package com.acme.platform.auth.infrastructure.redis;

import com.acme.platform.auth.api.AdminTokenIssued;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 扫码登录票据存储（REQ-20261119-040838 确认记录③，UploadTicketStore Lua 单次消费模板）：
 * 键 {@code auth:qr:ticket:{id}}（服务语义前缀 12.3.2），值 = 票据状态 JSON，SET + TTL 120s
 * （过期即 Redis TTL 自然失效→查询返回 expired，无显式清扫任务）。
 * 状态机（Lua 原子收敛，防与 confirm 竞态复活/回退已终态票）：
 * created →(info, CAS)→ scanned →(confirm, GET+DEL 取票＋回写)→ confirmed；created/scanned →(cancel, CAS)→ cancelled。
 * 确认成功后票据以 confirmed＋token 回写（剩余 TTL），TTL 内轮询可重复取令牌；二次 confirm 由状态校验拒绝
 * （QR_TICKET_INVALID，票据不被本次失败消费）。创建频控 {@code auth:qr:limit:{ip}} INCR+EXPIRE 60s
 * （LoginGuardRedis 先例：计数与过期非原子，频控场景容忍竞态窗口）。
 */
@Component
public class QrTicketStore {

    /** 票据键前缀（{id} 为 32 位 UUID）。 */
    public static final String KEY_PREFIX = "auth:qr:ticket:";

    /** 创建频控键前缀（按 IP）。 */
    public static final String LIMIT_KEY_PREFIX = "auth:qr:limit:";

    /** 缺省 TTL（确认记录③：120s）。 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(120);

    /** 票据状态（QrTicketStatusView 契约枚举；expired 仅作查询映射，不落存储）。 */
    public static final String STATUS_CREATED = "created";
    public static final String STATUS_SCANNED = "scanned";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_EXPIRED = "expired";

    /** 创建频控窗口（秒）。 */
    public static final long LIMIT_WINDOW_SECONDS = 60;

    /** 创建频控阈值（次/分钟/IP，确认记录④）。 */
    public static final int CREATION_LIMIT_PER_MINUTE = 10;

    /** 取票：GET + DEL 原子（Lua 单次消费，UploadTicketStore 模板）。 */
    private static final RedisScript<String> TAKE = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if v then redis.call('DEL', KEYS[1]) end
            return v
            """, String.class);

    /**
     * 扫码推进 created→scanned（CAS）：仅 created 才改写，改写以剩余 TTL 回写（不续期）；
     * 缺失（已过期）返回 nil 不写——杜绝与 confirm 取票竞态复活已删票据；
     * 已 scanned/confirmed/cancelled 原样返回（幂等不回退 confirmed）。
     */
    private static final RedisScript<String> MARK_SCANNED = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return nil end
            local obj = cjson.decode(v)
            if obj['status'] == 'created' then
                obj['status'] = 'scanned'
                local ttl = redis.call('TTL', KEYS[1])
                redis.call('SETEX', KEYS[1], math.max(ttl, 1), cjson.encode(obj))
            end
            return cjson.encode(obj)
            """, String.class);

    /**
     * 取消（CAS）：仅 created/scanned 可取消（确认中的 confirmed 不覆盖——不破坏轮询令牌面）；
     * 缺失返回 nil；已 cancelled 原样返回（幂等）。
     */
    private static final RedisScript<String> CANCEL = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return nil end
            local obj = cjson.decode(v)
            local s = obj['status']
            if s == 'created' or s == 'scanned' then
                obj['status'] = 'cancelled'
                local ttl = redis.call('TTL', KEYS[1])
                redis.call('SETEX', KEYS[1], math.max(ttl, 1), cjson.encode(obj))
            end
            return cjson.encode(obj)
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public QrTicketStore(StringRedisTemplate redisTemplate, JsonMapper jsonMapper) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 创建票据（created 态，TTL 120s）。
     *
     * @param createdIp 创建来源 IP（频控由用例层先行调用 {@link #tryAcquireCreationQuota}）
     * @return 票据 ID（32 位 UUID，无连字符）
     */
    public String create(String createdIp) {
        String ticketId = UUID.randomUUID().toString().replace("-", "");
        save(ticketId, new QrTicket(STATUS_CREATED, Instant.now().toString(), createdIp, null, null, null),
                DEFAULT_TTL);
        return ticketId;
    }

    /** 写票（SETEX）：确认回写（confirmed＋token）与失败归还共用，TTL 由调用方按剩余有效期传入。 */
    public void save(String ticketId, QrTicket ticket, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + ticketId, jsonMapper.writeValueAsString(ticket),
                Duration.ofSeconds(Math.max(ttl.getSeconds(), 1)));
    }

    /** 只读读票（轮询/确认预检）：缺失/过期/值损坏统一空（expired 由调用方映射）。 */
    public Optional<QrTicket> read(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + ticketId);
        return deserialize(json);
    }

    /** 取票（原子单次消费，Lua GET+DEL）：供 confirm 取得票面（状态权威校验在用例层）。 */
    public Optional<QrTicket> take(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        return deserialize(redisTemplate.execute(TAKE, List.of(KEY_PREFIX + ticketId)));
    }

    /**
     * 扫码推进（CAS 幂等）：created→scanned；已 scanned/confirmed 原样返回（不回退 confirmed）。
     *
     * @return 票据快照（含最终状态与 createdAt）；缺失/过期为空
     */
    public Optional<QrTicket> markScanned(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        return deserialize(redisTemplate.execute(MARK_SCANNED, List.of(KEY_PREFIX + ticketId)));
    }

    /**
     * 取消（CAS 幂等）：created/scanned→cancelled；缺失/已终态不写。
     *
     * @return 票据快照；缺失/过期为空（取消幂等成功由调用方收敛）
     */
    public Optional<QrTicket> cancel(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        return deserialize(redisTemplate.execute(CANCEL, List.of(KEY_PREFIX + ticketId)));
    }

    /**
     * 创建频控（INCR+EXPIRE 60s 窗口，阈值 10 次/分钟/IP；LoginGuardRedis 先例——计数与过期
     * 非原子，窗口竞态容忍）。
     *
     * @return true=未超限可创建；false=超限（调用方响应 QR_TICKET_LIMITED）
     */
    public boolean tryAcquireCreationQuota(String clientIp) {
        String key = LIMIT_KEY_PREFIX + clientIp;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(LIMIT_WINDOW_SECONDS));
        }
        return count != null && count <= CREATION_LIMIT_PER_MINUTE;
    }

    /** 值损坏按空处理（票据面不外泄内部异常，UploadTicketStore 同口径）。 */
    private Optional<QrTicket> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(json, QrTicket.class));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * 票据状态（确认记录③ JSON 形状：{status, createdAt, createdIp, confirmUserId?, confirmUsername?, token?}）：
     * token 为确认回写时存入的确认者 B 端令牌（供 TTL 内轮询重复取），其余状态为 null。
     *
     * @param status          票据状态（created/scanned/confirmed/cancelled）
     * @param createdAt       创建时间（ISO-8601；剩余 TTL 计算基准）
     * @param createdIp       创建来源 IP
     * @param confirmUserId   确认者 userId（confirm 回写时写入）
     * @param confirmUsername 确认者用户名（confirm 回写时写入）
     * @param token           确认者 B 端令牌（仅 confirmed 非空）
     */
    public record QrTicket(String status, String createdAt, String createdIp,
                           String confirmUserId, String confirmUsername, AdminTokenIssued token) {

        /** 失败归还态（状态回退为 scanned，APP 可重试确认；确认者与令牌清空）。 */
        public QrTicket asScanned() {
            return new QrTicket(STATUS_SCANNED, createdAt, createdIp, null, null, null);
        }
    }
}
