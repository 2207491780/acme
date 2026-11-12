package com.acme.framework.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-cache 配置属性（FR-022、Backend.md 8.1.3）：前缀 {@code acme.cache}，全字段默认值（架构设计 12）。
 */
@ConfigurationProperties("acme.cache")
public class CacheProperties {

    /** 模块开关（FR-030，默认 true）。 */
    private boolean enabled = true;

    /** L2 不可用处置（FR-005 异常分支，默认 DEGRADE）。 */
    private L2FailurePolicy l2Failure = L2FailurePolicy.DEGRADE;

    private final L1 l1 = new L1();

    private final L2 l2 = new L2();

    private final PenetrationGuard penetrationGuard = new PenetrationGuard();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public L2FailurePolicy getL2Failure() {
        return l2Failure;
    }

    public void setL2Failure(L2FailurePolicy l2Failure) {
        this.l2Failure = l2Failure;
    }

    public L1 getL1() {
        return l1;
    }

    public L2 getL2() {
        return l2;
    }

    public PenetrationGuard getPenetrationGuard() {
        return penetrationGuard;
    }

    /** L1（Caffeine）：条目上限 + TTL（广播失效兜底，FR-006）。 */
    public static class L1 {

        /** 条目上限（默认 10000）。 */
        private long maxSize = 10000;

        /** L1 TTL 秒（默认 60：广播失败兜底过期）。 */
        private long ttlSeconds = 60;

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    /** L2（Redis）：默认有限 TTL（防脏回填放大，M-12 一致性）。 */
    public static class L2 {

        /** L2 TTL 秒（默认 1800=30m；0=不设过期仅显式配置时允许）。 */
        private long ttlSeconds = 1800;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    /** 防击穿门（FR-007）。 */
    public static class PenetrationGuard {

        /** 防击穿开关（默认 true）。 */
        private boolean enabled = true;

        /** 入口锁租期毫秒（默认 3000）。 */
        private long leaseMillis = 3000;

        /** 未获权方等待上限毫秒（默认 200）。 */
        private long waitMillis = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getLeaseMillis() {
            return leaseMillis;
        }

        public void setLeaseMillis(long leaseMillis) {
            this.leaseMillis = leaseMillis;
        }

        public long getWaitMillis() {
            return waitMillis;
        }

        public void setWaitMillis(long waitMillis) {
            this.waitMillis = waitMillis;
        }
    }
}
