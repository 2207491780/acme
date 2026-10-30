package com.acme.common.util;

/**
 * 纯本地雪花 ID 生成器（FR-004；Q-002 结论：能力契约 + 纯本地雪花）。无 IO（BR-019）。
 *
 * <p>时钟回拨策略（DP-003 定稿）：小幅回拨（≤ {@value #MAX_BACKWARD_TOLERANCE_MILLIS} ms，NTP
 * 微调常见量级）自旋等待追平后继续发号；大幅回拨显式失败（IllegalStateException），
 * 不返回重复或非法 ID（BR-021），由运维介入对时后恢复。</p>
 *
 * <p>worker 分配策略（DP-003 定稿）：本类不做自动分配，由部署环境注入——容器序号
 * （StatefulSet 序号）或配置中心按实例分配，须保证同数据中心内唯一；
 * 取值域非法在构造期显式失败。framework-jpa 侧 {@code acme.jpa.id.worker-id} 必填校验与之呼应。</p>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public class SnowflakeIdGenerator implements IdGenerator {

    /** 骨架默认纪元（2026-01-01T00:00:00Z），随 DP-003 定稿。 */
    private static final long EPOCH_MILLIS = 1_767_225_600_000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = -1L ^ (-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = -1L ^ (-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = -1L ^ (-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    /** 时钟回拨容忍上限（毫秒，DP-003 定稿）：NTP 微调常见量级内自旋追平。 */
    static final long MAX_BACKWARD_TOLERANCE_MILLIS = 5L;

    private final long workerId;
    private final long datacenterId;

    private long sequence;
    private long lastTimestamp = -1L;

    /**
     * @param workerId     worker 标识（5 位取值域；分配策略见类级 javadoc，DP-003 定稿）
     * @param datacenterId 数据中心标识（5 位取值域校验）
     */
    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 越界：" + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId 越界：" + datacenterId);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /**
     * 生成全局唯一 ID（FR-004）；失败显式失败，不得返回重复或非法 ID（BR-021）。
     * 时钟回拨处置见类级 javadoc（DP-003 定稿）。
     */
    @Override
    public synchronized long nextId() {
        long timestamp = currentTime();
        if (timestamp < EPOCH_MILLIS) {
            throw new IllegalStateException("时间戳早于纪元，生成失败：" + timestamp);
        }
        if (timestamp < lastTimestamp) {
            long backward = lastTimestamp - timestamp;
            if (backward > MAX_BACKWARD_TOLERANCE_MILLIS) {
                throw new IllegalStateException("时钟回拨超过容忍阈值（" + backward + " ms），生成失败");
            }
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntil(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /** 当前时间（毫秒）；独立钩子供测试注入受控时钟。 */
    long currentTime() {
        return System.currentTimeMillis();
    }

    /** 自旋等待至不早于 target 的下一个时间戳。 */
    private long waitUntil(long target) {
        long timestamp = currentTime();
        while (timestamp <= target) {
            timestamp = currentTime();
        }
        return timestamp;
    }
}
