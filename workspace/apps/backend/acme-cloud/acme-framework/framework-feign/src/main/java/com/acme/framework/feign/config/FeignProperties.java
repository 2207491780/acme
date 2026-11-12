package com.acme.framework.feign.config;

import feign.Request;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * framework-feign 配置属性（FR-022、Backend.md 8.1.3）：前缀 {@code acme.feign}，全字段默认值（架构设计 12）。
 * 超时预算（10.4）：全局 connect/read + 按 feign client 名覆盖（{@code acme.feign.clients.*}）。
 */
@ConfigurationProperties("acme.feign")
public class FeignProperties {

    /** 模块开关（FR-030，默认 true）。 */
    private boolean enabled = true;

    /** 连接超时毫秒（10.4.2 显式，默认 3000；POC-2 同值先例）。 */
    private long connectTimeoutMillis = 3000;

    /** 读取超时毫秒（默认 5000）。 */
    private long readTimeoutMillis = 5000;

    private final Retry retry = new Retry();

    private final Circuit circuit = new Circuit();

    private final Token token = new Token();

    /** 按 feign client 名覆盖超时（缺省沿用全局值，10.4 超时预算）。 */
    private final Map<String, ClientOverride> clients = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public Retry getRetry() {
        return retry;
    }

    public Circuit getCircuit() {
        return circuit;
    }

    public Token getToken() {
        return token;
    }

    public Map<String, ClientOverride> getClients() {
        return clients;
    }

    /** 解析生效超时（按 client 名覆盖全局，10.4）。 */
    public Request.Options resolveOptions(String clientName, Request.Options defaults) {
        ClientOverride override = clientName == null ? null : clients.get(clientName);
        if (override == null) {
            return defaults != null ? defaults
                    : new Request.Options(Math.toIntExact(connectTimeoutMillis), Math.toIntExact(readTimeoutMillis));
        }
        int connect = override.getConnectTimeoutMillis() != null
                ? Math.toIntExact(override.getConnectTimeoutMillis())
                : (defaults != null ? defaults.connectTimeoutMillis() : Math.toIntExact(connectTimeoutMillis));
        int read = override.getReadTimeoutMillis() != null
                ? Math.toIntExact(override.getReadTimeoutMillis())
                : (defaults != null ? defaults.readTimeoutMillis() : Math.toIntExact(readTimeoutMillis));
        return new Request.Options(connect, read);
    }

    /** 单 client 超时覆盖（null=沿用全局/调用方缺省）。 */
    public static class ClientOverride {

        private Long connectTimeoutMillis;

        private Long readTimeoutMillis;

        public Long getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(Long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public Long getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(Long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }
    }

    /** 幂等重试（FR-019、10.3.3：仅幂等调用，指数退避+上限；默认 1 次防超预算——10.4）。 */
    public static class Retry {

        /** 重试次数含首次（默认 1=不重试，收紧防超时预算）。 */
        private int maxAttempts = 1;

        /** 退避初值毫秒（默认 100）。 */
        private long backoffInitialMillis = 100;

        /** 退避倍数（默认 2）。 */
        private double backoffMultiplier = 2;

        /** 退避上限毫秒（默认 1000）。 */
        private long backoffMaxMillis = 1000;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffInitialMillis() {
            return backoffInitialMillis;
        }

        public void setBackoffInitialMillis(long backoffInitialMillis) {
            this.backoffInitialMillis = backoffInitialMillis;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public long getBackoffMaxMillis() {
            return backoffMaxMillis;
        }

        public void setBackoffMaxMillis(long backoffMaxMillis) {
            this.backoffMaxMillis = backoffMaxMillis;
        }
    }

    /** 熔断（FR-020、Q-D004：裸 resilience4j）。 */
    public static class Circuit {

        /** 熔断开关（默认 true；false 时短路熔断包装直连 delegate）。 */
        private boolean enabled = true;

        /** 失败率阈值 %（默认 50）。 */
        private float failureRateThreshold = 50;

        /** 滑动窗口（默认 100）。 */
        private int slidingWindowSize = 100;

        /** 开启态等待秒（默认 10）。 */
        private int waitDurationInOpenStateSeconds = 10;

        /** 半开探测数（默认 10）。 */
        private int permittedCallsInHalfOpen = 10;

        /** 可配置忽略的状态码（默认空=仅 5xx 计失败；4xx 恒计成功）。 */
        private java.util.List<Integer> ignoreStatusCodes = new java.util.ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getWaitDurationInOpenStateSeconds() {
            return waitDurationInOpenStateSeconds;
        }

        public void setWaitDurationInOpenStateSeconds(int waitDurationInOpenStateSeconds) {
            this.waitDurationInOpenStateSeconds = waitDurationInOpenStateSeconds;
        }

        public int getPermittedCallsInHalfOpen() {
            return permittedCallsInHalfOpen;
        }

        public void setPermittedCallsInHalfOpen(int permittedCallsInHalfOpen) {
            this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
        }

        public java.util.List<Integer> getIgnoreStatusCodes() {
            return ignoreStatusCodes;
        }

        public void setIgnoreStatusCodes(java.util.List<Integer> ignoreStatusCodes) {
            this.ignoreStatusCodes = ignoreStatusCodes;
        }
    }

    /** 内部令牌注入（FR-022；签发配置收敛 acme.security.token.*，Q-D001；TTL 单点收敛 security 侧，Q-4）。 */
    public static class Token {

        /** 注入开关（默认 true）。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
