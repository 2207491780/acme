package com.acme.framework.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * framework-security 配置属性（FR-026、Backend.md 8.1.3）：前缀 {@code acme.security}，全字段默认值（架构设计 10）。
 */
@ConfigurationProperties("acme.security")
public class SecurityProperties {

    /** 模块开关（FR-026，默认 true）。 */
    private boolean enabled = true;

    /** 公开端点（BR-009，默认空）：放行且不写上下文。 */
    private List<String> publicPaths = new ArrayList<>();

    private final Token token = new Token();

    private final DataPermission dataPermission = new DataPermission();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public Token getToken() {
        return token;
    }

    public DataPermission getDataPermission() {
        return dataPermission;
    }

    /** 内部令牌校验配置（7.7，Q-D01；算法固定 HS256，原 algorithm 配置为死配置已移除）。 */
    public static class Token {

        /** HMAC 共享密钥（14.3.3 不入库不硬编码；默认空=启动即失败并提示注入；与用户令牌密钥域隔离，11.6.2）。 */
        private String hmacSecret = "";

        /** 签发方（7.7，默认 acme-gateway）。 */
        private String issuer = "acme-gateway";

        /** 签发有效时长（秒，Q-D001 收敛签发/校验两端配置，默认 300；claims exp，7.7）。 */
        private long ttlSeconds = 300;

        /** 时钟偏移（秒，7.7，默认 30）。 */
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

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public long getClockSkewSeconds() {
            return clockSkewSeconds;
        }

        public void setClockSkewSeconds(long clockSkewSeconds) {
            this.clockSkewSeconds = clockSkewSeconds;
        }
    }

    /** 数据权限拦截配置（FR-017）。 */
    public static class DataPermission {

        /** 数据权限拦截开关（FR-017，默认 true；无评估器=放行，Q-004）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
