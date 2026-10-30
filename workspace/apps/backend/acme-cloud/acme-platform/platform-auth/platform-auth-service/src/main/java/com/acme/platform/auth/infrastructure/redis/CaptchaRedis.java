package com.acme.platform.auth.infrastructure.redis;

import com.acme.platform.auth.config.AuthTokenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * C 端验证码 Redis 支撑（consumer-auth-refresh-dataperm）：验证码值 + 发送频控 + 失败计数，
 * 键带 auth: 服务前缀（12.3.2）。Redis 不可用语义：发送路径 fail-closed（无验证码存储即不发，
 * 防爆破场景不得静默放行）；登录校验路径值缺失按验证码错误处理。
 */
@Component
public class CaptchaRedis {

    private static final Logger log = LoggerFactory.getLogger(CaptchaRedis.class);

    static final String CODE_KEY_PREFIX = "auth:captcha:code:";
    static final String SEND_LOCK_PREFIX = "auth:captcha:send:";
    static final String FAIL_KEY_PREFIX = "auth:captcha:fail:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final AuthTokenProperties.Consumer properties;

    public CaptchaRedis(StringRedisTemplate redis, AuthTokenProperties properties) {
        this.redis = redis;
        this.properties = properties.getConsumer();
    }

    /**
     * 生成并存储验证码（发送频控命中返回 null）。
     *
     * @return 6 位验证码；频控窗口内返回 null（调用方响应 CAPTCHA_SEND_LIMITED）
     */
    public String generate(String phone) {
        Boolean first = redis.opsForValue().setIfAbsent(SEND_LOCK_PREFIX + phone, "1",
                Duration.ofSeconds(properties.getCaptchaSendIntervalSeconds()));
        if (!Boolean.TRUE.equals(first)) {
            return null;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(CODE_KEY_PREFIX + phone, code,
                Duration.ofSeconds(properties.getCaptchaTtlSeconds()));
        redis.delete(FAIL_KEY_PREFIX + phone);
        return code;
    }

    /**
     * 校验验证码（一次性消费语义：成功即删；失败计数达阈值作废）。
     *
     * @return true=校验通过
     */
    public boolean verify(String phone, String captcha) {
        String stored = redis.opsForValue().get(CODE_KEY_PREFIX + phone);
        if (stored == null) {
            return false;
        }
        if (stored.equals(captcha)) {
            redis.delete(CODE_KEY_PREFIX + phone);
            redis.delete(FAIL_KEY_PREFIX + phone);
            return true;
        }
        Long failures = redis.opsForValue().increment(FAIL_KEY_PREFIX + phone);
        if (failures != null && failures == 1L) {
            redis.expire(FAIL_KEY_PREFIX + phone, Duration.ofSeconds(properties.getCaptchaTtlSeconds()));
        }
        if (failures != null && failures >= properties.getCaptchaMaxFailures()) {
            redis.delete(CODE_KEY_PREFIX + phone);
            redis.delete(FAIL_KEY_PREFIX + phone);
        }
        return false;
    }

    /** 验证码是否仍在有效期（过期与错误区分提示用；Redis 异常按已过期处理）。 */
    public boolean exists(String phone) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(CODE_KEY_PREFIX + phone));
        } catch (RuntimeException ex) {
            log.warn("验证码存在性检查失败，按已过期处理：phone={}", phone);
            return false;
        }
    }
}
