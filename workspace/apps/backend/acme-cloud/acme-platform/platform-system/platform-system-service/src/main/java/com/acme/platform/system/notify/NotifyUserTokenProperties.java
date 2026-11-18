package com.acme.platform.system.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WS 握手用户令牌验签属性（REQ-20261114-120102；确认记录⑬钉死键=网关同键单源
 * {@code acme.gateway.user-token.hmac-secret}）：与 framework-gateway GatewayProperties.UserToken
 * 同键同默认值（issuer=acme-auth、clockSkewSeconds=30），环境注入一次同时供网关与本服务
 * WS 握手拦截器验签（用户令牌域 ACME_USER_TOKEN_HMAC_SECRET，与内部令牌域隔离，11.6.2）。
 * 字段语义镜像网关（Backend.md 11.6.1 验签语义单源），仅承载校验所需三键。
 */
@ConfigurationProperties("acme.gateway.user-token")
public class NotifyUserTokenProperties {

    /** 用户令牌 HMAC 密钥（14.3.3 不入库不硬编码；默认空=启动即失败并提示注入）。 */
    private String hmacSecret = "";

    /** 期望签发方（platform-auth，默认 acme-auth；验签强制匹配）。 */
    private String issuer = "acme-auth";

    /** 时钟偏移（秒，默认 30，与网关一致）。 */
    private long clockSkewSeconds = 30;

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

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }
}
