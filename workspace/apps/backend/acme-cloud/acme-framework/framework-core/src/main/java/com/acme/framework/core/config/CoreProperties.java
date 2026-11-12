package com.acme.framework.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-core 配置属性（FR-006、BR-006、Backend.md 8.1.3）：前缀 {@code acme.core}，全字段默认值（NFR-005）。
 */
@ConfigurationProperties("acme.core")
public class CoreProperties {

    /** 模块开关（FR-006；Q-001 默认 true）。 */
    private boolean enabled = true;

    private final Executor executor = new Executor();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Executor getExecutor() {
        return executor;
    }

    /** 线程池配置（架构设计 6 配置项全表；FR-001）。 */
    public static class Executor {

        private int corePoolSize = 8;

        private int maxPoolSize = 32;

        private int queueCapacity = 1000;

        private int keepAliveSeconds = 60;

        private String threadNamePrefix = "acme-core-";

        private RejectionPolicy rejectionPolicy = RejectionPolicy.ABORT;

        private boolean waitForTasksToCompleteOnShutdown = true;

        private int awaitTerminationSeconds = 20;

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public RejectionPolicy getRejectionPolicy() {
            return rejectionPolicy;
        }

        public void setRejectionPolicy(RejectionPolicy rejectionPolicy) {
            this.rejectionPolicy = rejectionPolicy;
        }

        public boolean isWaitForTasksToCompleteOnShutdown() {
            return waitForTasksToCompleteOnShutdown;
        }

        public void setWaitForTasksToCompleteOnShutdown(boolean waitForTasksToCompleteOnShutdown) {
            this.waitForTasksToCompleteOnShutdown = waitForTasksToCompleteOnShutdown;
        }

        public int getAwaitTerminationSeconds() {
            return awaitTerminationSeconds;
        }

        public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
            this.awaitTerminationSeconds = awaitTerminationSeconds;
        }
    }
}
