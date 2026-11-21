package com.acme.platform.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调度域配置属性（前缀 acme.scheduler；application.yml 缺省值对齐契约确认④⑤终稿）。
 */
@ConfigurationProperties(prefix = "acme.scheduler")
public class SchedulerProperties {

    private final Dispatch dispatch = new Dispatch();
    private final Executor executor = new Executor();
    private final Kernel kernel = new Kernel();

    /** 下发通道参数。 */
    public static class Dispatch {

        /** 下发接单超时毫秒（确认⑤：10s 未 200 → FAILED(error=DISPATCH_TIMEOUT) 走重试）。 */
        private long timeoutMs = 10_000L;

        /** 下发线程池核心数（IO 密集独立于 tick 线程，设计规格 §3.2）。 */
        private int poolSize = 8;

        /** 下发线程池队列上限（满时提交失败→FAILED(error=DISPATCH_FAILED) 走重试）。 */
        private int queueCapacity = 200;

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /** 执行器面参数。 */
    public static class Executor {

        /** 心跳 stale 阈值秒（确认④：30s 无心跳 OFFLINE）。 */
        private int heartbeatStaleSeconds = 30;

        public int getHeartbeatStaleSeconds() {
            return heartbeatStaleSeconds;
        }

        public void setHeartbeatStaleSeconds(int heartbeatStaleSeconds) {
            this.heartbeatStaleSeconds = heartbeatStaleSeconds;
        }
    }

    /** 内核时间窗参数（测试可控时钟注入经 common-core Clock bean 覆盖）。 */
    public static class Kernel {

        /** 时间轮回填窗口秒（tick 每秒把窗口内到期任务注册进轮）。 */
        private int wheelHorizonSeconds = 60;

        /** misfire 判定的 tick 容差毫秒（下一期已过期=整周期错过，进入 misfire 分支）。 */
        private long misfireGraceMs = 1_000L;

        /** 每次 tick 兜底扫描/下发扫描的批上限。 */
        private int scanBatchSize = 200;

        public int getWheelHorizonSeconds() {
            return wheelHorizonSeconds;
        }

        public void setWheelHorizonSeconds(int wheelHorizonSeconds) {
            this.wheelHorizonSeconds = wheelHorizonSeconds;
        }

        public long getMisfireGraceMs() {
            return misfireGraceMs;
        }

        public void setMisfireGraceMs(long misfireGraceMs) {
            this.misfireGraceMs = misfireGraceMs;
        }

        public int getScanBatchSize() {
            return scanBatchSize;
        }

        public void setScanBatchSize(int scanBatchSize) {
            this.scanBatchSize = scanBatchSize;
        }
    }

    public Dispatch getDispatch() {
        return dispatch;
    }

    public Executor getExecutor() {
        return executor;
    }

    public Kernel getKernel() {
        return kernel;
    }
}
