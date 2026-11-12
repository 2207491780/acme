package com.acme.framework.stream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * framework-stream 配置属性（FR-022、Backend.md 8.1.3）：前缀 {@code acme.stream}，全字段默认值（架构设计 12）。
 */
@ConfigurationProperties("acme.stream")
public class StreamProperties {

    /** 模块开关（FR-030，默认 true）。 */
    private boolean enabled = true;

    private final Dlq dlq = new Dlq();

    private final Idempotency idempotency = new Idempotency();

    private final Retry retry = new Retry();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Dlq getDlq() {
        return dlq;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public Retry getRetry() {
        return retry;
    }

    /** 死信通道启动校验（FR-015）。 */
    public static class Dlq {

        /** 死信校验开关（默认 true：缺失则启动显式失败）。 */
        private boolean required = true;

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    /** 消费幂等（FR-014，Q-002 Redis 去重键；去重门不可关闭——取舍 #5）。 */
    public static class Idempotency {

        /** 去重键完成 TTL 小时（默认 72）。 */
        private long ttlHours = 72;

        /** 在途占位 TTL（默认 PT10M；崩溃残留过期后可重投再认领，M-12）。 */
        private Duration processingTtl = Duration.ofMinutes(10);

        /** 幂等键缺失处置（默认 REJECT）。 */
        private MissingIdempotentKeyPolicy missingKeyPolicy = MissingIdempotentKeyPolicy.REJECT;

        public long getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(long ttlHours) {
            this.ttlHours = ttlHours;
        }

        public Duration getProcessingTtl() {
            return processingTtl;
        }

        public void setProcessingTtl(Duration processingTtl) {
            this.processingTtl = processingTtl;
        }

        public MissingIdempotentKeyPolicy getMissingKeyPolicy() {
            return missingKeyPolicy;
        }

        public void setMissingKeyPolicy(MissingIdempotentKeyPolicy missingKeyPolicy) {
            this.missingKeyPolicy = missingKeyPolicy;
        }
    }

    /** 消费重试（FR-015、10.3.3 指数退避+上限）：映射为各 consumer 绑定的 max-attempts/退避参数。 */
    public static class Retry {

        /** 重试次数含首次（默认 3）。 */
        private int maxAttempts = 3;

        /** 退避初值毫秒（默认 100）。 */
        private long backoffInitialMillis = 100;

        /** 退避倍数（默认 2）。 */
        private double backoffMultiplier = 2;

        /** 退避上限毫秒（默认 2000）。 */
        private long backoffMaxMillis = 2000;

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
}
