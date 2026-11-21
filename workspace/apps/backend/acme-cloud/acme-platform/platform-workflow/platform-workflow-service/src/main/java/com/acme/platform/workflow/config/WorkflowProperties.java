package com.acme.platform.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * workflow 域配置属性（前缀 acme.workflow；缺省值经本类静态化，运维面调整经 Nacos data-id 覆盖）。
 */
@ConfigurationProperties(prefix = "acme.workflow")
public class WorkflowProperties {

    /** 定时联动（确认⑥）：本地 ScheduledExecutorService 兜底 + 注册失败重试。 */
    private final Timer timer = new Timer();

    /** ServiceTask 委托（确认⑤/§7）：下发超时与重试缺省。 */
    private final Delegate delegate = new Delegate();

    /** 身份目录快照（流程引擎本地快照方案）：Feign 查询结果的本地缓存 TTL。 */
    private final Identity identity = new Identity();

    /** 定时联动参数。 */
    public static class Timer {

        /** 兜底扫描轮询间隔毫秒。 */
        private long fallbackPollMs = 5_000L;

        /** 注册失败重试间隔毫秒（后台重试注册直至成功后移除本地兜底条目）。 */
        private long retryRegisterIntervalMs = 10_000L;

        public long getFallbackPollMs() {
            return fallbackPollMs;
        }

        public void setFallbackPollMs(long fallbackPollMs) {
            this.fallbackPollMs = fallbackPollMs;
        }

        public long getRetryRegisterIntervalMs() {
            return retryRegisterIntervalMs;
        }

        public void setRetryRegisterIntervalMs(long retryRegisterIntervalMs) {
            this.retryRegisterIntervalMs = retryRegisterIntervalMs;
        }
    }

    /** ServiceTask 委托参数（acme: 属性缺省值的兜底）。 */
    public static class Delegate {

        /** 单次下发超时毫秒（acme:timeoutMs 未配置时取值）。 */
        private long timeoutMs = 10_000L;

        /** 重试上限（acme:retryMaxAttempts 未配置时取值；0=不重试）。 */
        private int retryMaxAttempts = 0;

        /** 重试首间隔毫秒（acme:retryBackoffMs 未配置时取值）。 */
        private long retryBackoffMs = 1_000L;

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getRetryMaxAttempts() {
            return retryMaxAttempts;
        }

        public void setRetryMaxAttempts(int retryMaxAttempts) {
            this.retryMaxAttempts = retryMaxAttempts;
        }

        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
        }
    }

    /** 身份目录快照参数。 */
    public static class Identity {

        /** 本地快照缓存 TTL 毫秒（0=不缓存）。 */
        private long snapshotTtlMs = 30_000L;

        /** 快照条目上限（防御性：超限直接绕过缓存）。 */
        private int snapshotMaxEntries = 5_000;

        public long getSnapshotTtlMs() {
            return snapshotTtlMs;
        }

        public void setSnapshotTtlMs(long snapshotTtlMs) {
            this.snapshotTtlMs = snapshotTtlMs;
        }

        public int getSnapshotMaxEntries() {
            return snapshotMaxEntries;
        }

        public void setSnapshotMaxEntries(int snapshotMaxEntries) {
            this.snapshotMaxEntries = snapshotMaxEntries;
        }
    }

    public Timer getTimer() {
        return timer;
    }

    public Delegate getDelegate() {
        return delegate;
    }

    public Identity getIdentity() {
        return identity;
    }
}
