package com.acme.platform.scheduler.kernel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 内存分层时间轮（设计规格 §3.1 终稿：秒 60 槽 + 分 60 槽分层降级，纯 JDK 并发结构自研、不引依赖；
 * 正确性事实源恒为 PG——本轮仅为执行精度加速层，DB 兜底扫描并存，轮内条目丢失不影响正确性）。
 *
 * <p>结构：任务按「距当前秒的延迟」分流——
 * <ul>
 *   <li>延迟 &lt; 60s：入秒轮（槽位 = fireEpochSecond % 60，条目携带绝对触发秒）；</li>
 *   <li>延迟 ≤ 3600s：入分轮（槽位 = floor(fireEpochSecond/60) % 60，条目携带目标分钟）；</li>
 *   <li>延迟 &gt; 3600s：不入轮（留在 PG，由 tick 回填窗口逐级拉近）。</li>
 * </ul>
 * tick 每秒驱动 {@link #advance}：先推进分指针（级联本分钟到期的分轮槽入秒轮），再回收秒轮当前槽。
 * 并发口径：tick 线程为唯一 advance/级联驱动方（ShedLock 单发），API 线程可并发 register/remove——
 * 槽容器用 {@link ConcurrentHashMap} + {@link CopyOnWriteArraySet}，读多写小，无锁竞争热点。</p>
 */
@Component
public class JobTimeWheel {

    private static final Logger log = LoggerFactory.getLogger(JobTimeWheel.class);

    /** 秒轮槽数（60 槽 × 1s）。 */
    static final int SECOND_SLOTS = 60;

    /** 分轮槽数（60 槽 × 1min；总覆盖窗 3600s）。 */
    static final int MINUTE_SLOTS = 60;

    /** 秒轮条目（绝对触发秒锚定，跨槽回收按值过滤，指针漂移免疫）。 */
    private record SecondEntry(long jobId, long fireEpochSecond, Instant fireTime) {
    }

    /** 分轮条目（目标分钟锚定）。 */
    private record MinuteEntry(long jobId, long targetMinute, Instant fireTime) {
    }

    private final List<Set<SecondEntry>> secondSlots;
    private final List<Map<Long, MinuteEntry>> minuteSlots;

    /** 已注册任务索引（jobId → 触发时刻；幂等重注册与 remove 支撑）。 */
    private final ConcurrentHashMap<Long, Instant> registered = new ConcurrentHashMap<>();

    /** 分指针当前分钟（级联判界）。 */
    private volatile long cascadedMinute = Long.MIN_VALUE;

    public JobTimeWheel() {
        List<Set<SecondEntry>> seconds = new ArrayList<>(SECOND_SLOTS);
        for (int i = 0; i < SECOND_SLOTS; i++) {
            seconds.add(new CopyOnWriteArraySet<>());
        }
        this.secondSlots = List.copyOf(seconds);
        List<Map<Long, MinuteEntry>> minutes = new ArrayList<>(MINUTE_SLOTS);
        for (int i = 0; i < MINUTE_SLOTS; i++) {
            minutes.add(new ConcurrentHashMap<>());
        }
        this.minuteSlots = List.copyOf(minutes);
    }

    /**
     * 注册任务触发点（幂等：同 jobId 重复注册以最后一次为准）。
     *
     * @return true=已入轮；false=超出轮覆盖窗（留 PG 兜底）
     */
    public boolean register(long jobId, Instant fireTime, Instant now) {
        long deltaSeconds = Math.max(0, fireTime.getEpochSecond() - now.getEpochSecond());
        remove(jobId);
        if (deltaSeconds >= SECOND_SLOTS * MINUTE_SLOTS) {
            return false;
        }
        if (deltaSeconds < SECOND_SLOTS) {
            long fireSecond = fireTime.getEpochSecond();
            secondSlots.get(Math.floorMod(fireSecond, SECOND_SLOTS))
                    .add(new SecondEntry(jobId, fireSecond, fireTime));
        } else {
            long targetMinute = Math.floorDiv(fireTime.getEpochSecond(), 60L);
            minuteSlots.get(Math.floorMod(targetMinute, MINUTE_SLOTS)).put(jobId,
                    new MinuteEntry(jobId, targetMinute, fireTime));
        }
        registered.put(jobId, fireTime);
        return true;
    }

    /** 移除任务（定义更新/删除/触发完成后的轮内清理；幂等）。 */
    public void remove(long jobId) {
        Instant previous = registered.remove(jobId);
        if (previous == null) {
            return;
        }
        long fireSecond = previous.getEpochSecond();
        secondSlots.get(Math.floorMod(fireSecond, SECOND_SLOTS))
                .removeIf(entry -> entry.jobId() == jobId);
        long targetMinute = Math.floorDiv(fireSecond, 60L);
        minuteSlots.get(Math.floorMod(targetMinute, MINUTE_SLOTS)).remove(jobId);
    }

    /** 是否已注册（jobId 维度，含任意触发时刻）。 */
    public boolean contains(long jobId) {
        return registered.containsKey(jobId);
    }

    /**
     * 推进时间轮到 now 并回收到期条目（tick 每秒驱动）。
     *
     * @return 本秒到期 (jobId, fireTime) 列表（按触发时刻升序）
     */
    public List<DueEntry> advance(Instant now) {
        List<DueEntry> due = new ArrayList<>();
        long currentMinute = Math.floorDiv(now.getEpochSecond(), 60L);
        // 1. 分指针级联：当前分钟到期的分轮条目全部拉近秒轮（含停顿补进的窗口）
        cascadeMinute(currentMinute);
        // 2. 秒轮回收：当前槽内触发秒 == now 的条目出轮；过时条目顺带清理
        Set<SecondEntry> slot = secondSlots.get(Math.floorMod(now.getEpochSecond(), SECOND_SLOTS));
        for (SecondEntry entry : slot) {
            if (entry.fireEpochSecond() == now.getEpochSecond()) {
                slot.remove(entry);
                registered.remove(entry.jobId(), entry.fireTime());
                due.add(new DueEntry(entry.jobId(), entry.fireTime()));
            } else if (entry.fireEpochSecond() < now.getEpochSecond()) {
                slot.remove(entry);
                registered.remove(entry.jobId(), entry.fireTime());
            }
        }
        if (!due.isEmpty()) {
            log.debug("时间轮到期 {} 项 @ {}", due.size(), now);
        }
        return due;
    }

    /** 分轮级联（首次调用初始化指针到当前分钟，避免回放历史分钟）。 */
    private void cascadeMinute(long currentMinute) {
        long previous = cascadedMinute;
        if (previous == Long.MIN_VALUE) {
            cascadedMinute = currentMinute;
            return;
        }
        if (currentMinute == previous) {
            return;
        }
        long from = Math.max(previous + 1, currentMinute - MINUTE_SLOTS);
        for (long minute = from; minute <= currentMinute; minute++) {
            Map<Long, MinuteEntry> bucket = minuteSlots.get(Math.floorMod(minute, MINUTE_SLOTS));
            for (Map.Entry<Long, MinuteEntry> entry : bucket.entrySet()) {
                if (entry.getValue().targetMinute() == minute) {
                    bucket.remove(entry.getKey());
                    registered.remove(entry.getKey(), entry.getValue().fireTime());
                    long fireSecond = entry.getValue().fireTime().getEpochSecond();
                    secondSlots.get(Math.floorMod(fireSecond, SECOND_SLOTS))
                            .add(new SecondEntry(entry.getKey(), fireSecond, entry.getValue().fireTime()));
                    registered.put(entry.getKey(), entry.getValue().fireTime());
                }
            }
        }
        cascadedMinute = currentMinute;
    }

    /** 到期条目（jobId + 计划触发时刻）。 */
    public record DueEntry(long jobId, Instant fireTime) {
    }
}
