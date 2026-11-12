package com.acme.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花 ID 生成器测试（AC-005、架构设计 6.2；DP-003 定稿）：
 * 唯一性、正数、取值域校验与时钟回拨策略（小幅自旋追平、大幅显式失败）。
 */
class SnowflakeIdGeneratorTest {

    @Test
    void constructibleAndNextIdNeverRepeats() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(generator.nextId());
        }
        assertEquals(1000, ids.size());
    }

    @Test
    void idsArePositive() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0);
        assertTrue(generator.nextId() > 0);
    }

    @Test
    void workerIdOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(0, 32));
    }

    /**
     * 小幅回拨（≤ 容忍阈值）：自旋等待追平后继续发号，不抛异常。
     * 受控时钟每次读取自增 1ms，模拟等待期间时间自然前进（否则自旋不终止）。
     */
    @Test
    void smallBackwardDriftWaitsAndRecovers() {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        ControlledClockGenerator generator = new ControlledClockGenerator(clock, 1, 1);
        long first = generator.nextId();

        clock.set(clock.get() - 3); // 3ms 回拨，阈值内
        long second = generator.nextId();
        assertTrue(second > first, "追平后发号应前进");

        long third = generator.nextId();
        assertTrue(third > second);
    }

    /** 大幅回拨（> 容忍阈值）：显式失败，不返回重复或非法 ID（BR-021）。 */
    @Test
    void largeBackwardDriftFailsExplicitly() {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        ControlledClockGenerator generator = new ControlledClockGenerator(clock, 1, 1);
        generator.nextId();

        long now = clock.get();
        clock.set(now - SnowflakeIdGenerator.MAX_BACKWARD_TOLERANCE_MILLIS - 100);
        assertThrows(IllegalStateException.class, generator::nextId);
    }

    /** 受控时钟钩子（覆盖 currentTime 供回拨场景注入；每次读取自增 1ms 模拟时间前进）。 */
    static final class ControlledClockGenerator extends SnowflakeIdGenerator {

        private final AtomicLong clock;

        ControlledClockGenerator(AtomicLong clock, long workerId, long datacenterId) {
            super(workerId, datacenterId);
            this.clock = clock;
        }

        @Override
        long currentTime() {
            return clock.getAndIncrement();
        }
    }
}
