package com.acme.platform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户令牌签发配置（架构设计 9.3；前缀 {@code acme.auth.token}）。
 * 密钥与网关 {@code acme.gateway.user-token.hmac-secret} 同值（auth 为签发方、网关为验签方）；
 * 空密钥启动即失败（TokenKeys 单一失败点，14.3.3）。
 */
@ConfigurationProperties("acme.auth.token")
public class AuthTokenProperties {

    /** HMAC 密钥（14.3.3 经环境注入；默认空=启动失败提示注入）。 */
    private String hmacSecret = "";

    /** 签发方标识（与网关 issuer 配置对齐）。 */
    private String issuer = "acme-auth";

    /** B 端短会话 TTL（秒，默认 1800=30 分钟）。 */
    private long ttlSeconds = 1800;

    /** C 端双令牌 TTL（consumer-auth-refresh-dataperm：默认 access 2h / refresh 14d，11.4）。 */
    private Consumer consumer = new Consumer();

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public void setConsumer(Consumer consumer) {
        this.consumer = consumer;
    }

    /** C 端令牌 TTL 与验证码策略。 */
    public static class Consumer {

        /** C 端访问令牌 TTL（秒，默认 7200=2 小时）。 */
        private long accessTtlSeconds = 7200;

        /** C 端刷新令牌 TTL（秒，默认 1209600=14 天）。 */
        private long refreshTtlSeconds = 1209600;

        /** 验证码有效期（秒，默认 300=5 分钟）。 */
        private int captchaTtlSeconds = 300;

        /** 验证码发送频控（秒/手机号，默认 60）。 */
        private int captchaSendIntervalSeconds = 60;

        /** 验证码最大失败次数（达阈值作废，需重发；默认 5）。 */
        private int captchaMaxFailures = 5;

        public long getAccessTtlSeconds() {
            return accessTtlSeconds;
        }

        public void setAccessTtlSeconds(long accessTtlSeconds) {
            this.accessTtlSeconds = accessTtlSeconds;
        }

        public long getRefreshTtlSeconds() {
            return refreshTtlSeconds;
        }

        public void setRefreshTtlSeconds(long refreshTtlSeconds) {
            this.refreshTtlSeconds = refreshTtlSeconds;
        }

        public int getCaptchaTtlSeconds() {
            return captchaTtlSeconds;
        }

        public void setCaptchaTtlSeconds(int captchaTtlSeconds) {
            this.captchaTtlSeconds = captchaTtlSeconds;
        }

        public int getCaptchaSendIntervalSeconds() {
            return captchaSendIntervalSeconds;
        }

        public void setCaptchaSendIntervalSeconds(int captchaSendIntervalSeconds) {
            this.captchaSendIntervalSeconds = captchaSendIntervalSeconds;
        }

        public int getCaptchaMaxFailures() {
            return captchaMaxFailures;
        }

        public void setCaptchaMaxFailures(int captchaMaxFailures) {
            this.captchaMaxFailures = captchaMaxFailures;
        }
    }
}
