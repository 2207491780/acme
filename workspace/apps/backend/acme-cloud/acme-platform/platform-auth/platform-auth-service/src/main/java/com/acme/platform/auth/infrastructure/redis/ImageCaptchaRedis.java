package com.acme.platform.auth.infrastructure.redis;

import com.acme.platform.auth.config.AuthTokenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * B 端图形验证码 Redis 支撑（login-guard-captcha）：captchaId → 4 位字符码，键带 auth: 服务前缀
 * （12.3.2，与短信验证码键域隔离）。Redis 不可用语义与短信验证码同型：生成路径 fail-closed
 * （无存储即不返回验证码），校验路径值缺失按验证码错误处理。
 */
@Component
public class ImageCaptchaRedis {

    private static final Logger log = LoggerFactory.getLogger(ImageCaptchaRedis.class);

    static final String CODE_KEY_PREFIX = "auth:image-captcha:code:";
    static final String FAIL_KEY_PREFIX = "auth:image-captcha:fail:";

    private final StringRedisTemplate redis;
    private final AuthTokenProperties properties;

    public ImageCaptchaRedis(StringRedisTemplate redis, AuthTokenProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 存储验证码（captchaId → 4 位字符码，TTL 取 B 端图形验证码有效期）。 */
    public void store(String captchaId, String code) {
        redis.opsForValue().set(CODE_KEY_PREFIX + captchaId, code,
                Duration.ofSeconds(properties.getAdmin().getCaptchaTtlSeconds()));
        redis.delete(FAIL_KEY_PREFIX + captchaId);
    }

    /**
     * 校验图形验证码（一次性消费语义：成功即删；失败计数达阈值作废，防在线识别爆破）。
     * 字符码忽略大小写比较（与主流图形验证码 UX 一致）。
     *
     * @return true=校验通过
     */
    public boolean verify(String captchaId, String code) {
        String stored = redis.opsForValue().get(CODE_KEY_PREFIX + captchaId);
        if (stored == null) {
            return false;
        }
        if (stored.equalsIgnoreCase(code == null ? "" : code.trim())) {
            redis.delete(CODE_KEY_PREFIX + captchaId);
            redis.delete(FAIL_KEY_PREFIX + captchaId);
            return true;
        }
        Long failures = redis.opsForValue().increment(FAIL_KEY_PREFIX + captchaId);
        if (failures != null && failures == 1L) {
            redis.expire(FAIL_KEY_PREFIX + captchaId,
                    Duration.ofSeconds(properties.getAdmin().getCaptchaTtlSeconds()));
        }
        if (failures != null && failures >= properties.getAdmin().getCaptchaMaxFailures()) {
            redis.delete(CODE_KEY_PREFIX + captchaId);
            redis.delete(FAIL_KEY_PREFIX + captchaId);
        }
        return false;
    }

    /** 验证码是否仍在有效期（过期与错误区分提示用；Redis 异常按已过期处理）。 */
    public boolean exists(String captchaId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(CODE_KEY_PREFIX + captchaId));
        } catch (RuntimeException ex) {
            log.warn("图形验证码存在性检查失败，按已过期处理：captchaId={}", captchaId, ex);
            return false;
        }
    }
}
