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

    /** 执行器 SDK（REQ-20261121-200724 确认④，前缀 acme.job.executor）。 */
    private final Executor executor = new Executor();

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

    public Executor getExecutor() {
        return executor;
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

    /**
     * 执行器 SDK 装配（REQ-20261121-200724 确认④，前缀 acme.job.executor）：
     * 总开关 enabled（默认 true；bootstrap 单体 application-monolith.yml 显式 false=纯本地模式——
     * 零注册零接单，本地 @Scheduled 全量照跑）；心跳周期默认 10s（≤ stale 阈值 30s 一半）。
     */
    public static class Executor {

        /** 执行器总开关（默认 true；false=纯本地模式，不装配注册/心跳/接单面）。 */
        private boolean enabled = true;

        /** 心跳周期毫秒（默认 10s，可配；须 ≤ 调度侧 stale 阈值一半）。 */
        private long heartbeatIntervalMs = 10_000;

        /** 首次注册延迟毫秒（0=就绪即注册；避免与测试/下发窗口竞态）。 */
        private long registerInitialDelayMs = 0;

        /** 执行器 appName 覆盖（空=spring.application.name）。 */
        private String appName = "";

        /** 调度中心服务名（lb:// 寻址目标；默认 platform-scheduler）。 */
        private String schedulerServiceName = "platform-scheduler";

        /** 下发执行线程池大小（接单后异步执行；池满不排队——接单拒绝走调度侧重试链）。 */
        private int runPoolSize = 4;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getHeartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }

        public long getRegisterInitialDelayMs() {
            return registerInitialDelayMs;
        }

        public void setRegisterInitialDelayMs(long registerInitialDelayMs) {
            this.registerInitialDelayMs = registerInitialDelayMs;
        }

        public String getAppName() {
            return appName;
        }

        public void setAppName(String appName) {
            this.appName = appName;
        }

        public String getSchedulerServiceName() {
            return schedulerServiceName;
        }

        public void setSchedulerServiceName(String schedulerServiceName) {
            this.schedulerServiceName = schedulerServiceName;
        }

        public int getRunPoolSize() {
            return runPoolSize;
        }

        public void setRunPoolSize(int runPoolSize) {
            this.runPoolSize = runPoolSize;
        }
    }
}
