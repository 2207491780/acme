package com.acme.framework.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * framework-job 配置属性（FR-022、Backend.md 8.1.3）：前缀 {@code acme.job}，全字段默认值（架构设计 12）。
 */
@ConfigurationProperties("acme.job")
public class JobProperties {

    /** 模块开关（FR-030，默认 true）。 */
    private boolean enabled = true;

    private final Shedlock shedlock = new Shedlock();

    /** 锁最长持有（默认 PT30M，ShedLock Duration 口径）。 */
    private Duration lockAtMostFor = Duration.ofMinutes(30);

    /** 锁最短持有（默认 PT1M）。 */
    private Duration lockAtLeastFor = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Shedlock getShedlock() {
        return shedlock;
    }

    public Duration getLockAtMostFor() {
        return lockAtMostFor;
    }

    public void setLockAtMostFor(Duration lockAtMostFor) {
        this.lockAtMostFor = lockAtMostFor;
    }

    public Duration getLockAtLeastFor() {
        return lockAtLeastFor;
    }

    public void setLockAtLeastFor(Duration lockAtLeastFor) {
        this.lockAtLeastFor = lockAtLeastFor;
    }

    /** ShedLock 装配（FR-025）。 */
    public static class Shedlock {

        /** 装配模式（默认 AUTO）。 */
        private ShedLockMode mode = ShedLockMode.AUTO;

        public ShedLockMode getMode() {
            return mode;
        }

        public void setMode(ShedLockMode mode) {
            this.mode = mode;
        }
    }
}
