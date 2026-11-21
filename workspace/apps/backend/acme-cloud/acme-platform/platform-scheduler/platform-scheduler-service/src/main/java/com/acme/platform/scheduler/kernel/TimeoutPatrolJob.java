package com.acme.platform.scheduler.kernel;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时巡检调度入口（REQ-20261121-071002 确认⑤终稿）：fixedDelay=10s 独立巡检 +
 * ShedLock 单发（与 tick 同锁名 platform-scheduler-tick、同 sch_scheduler_lock 单锁行——
 * 「同 ShedLock」口径：巡检频率 10s 与 tick 1s 共锁无实际争用，且选主锁行保持单行确定性）。
 * 职责见 {@link SchedulerKernel#patrol()}：RUNNING 超时 / 孤儿执行恢复 / 执行器失联回写。
 */
@Component
public class TimeoutPatrolJob {

    private static final Logger log = LoggerFactory.getLogger(TimeoutPatrolJob.class);

    private final SchedulerKernel kernel;

    public TimeoutPatrolJob(SchedulerKernel kernel) {
        this.kernel = kernel;
    }

    /** 每 10s 巡检（与 tick 同 provider 独立锁名：持锁实例执行（确认⑤「同 ShedLock 或独立锁名」取独立锁名口径，锁行按名分离））。 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    @SchedulerLock(name = "platform-scheduler-patrol", lockAtMostFor = "30s", lockAtLeastFor = "1s")
    public void patrol() {
        try {
            kernel.patrol();
        } catch (RuntimeException ex) {
            log.error("巡检执行异常（下轮继续）: {}", ex.getMessage(), ex);
        }
    }
}
