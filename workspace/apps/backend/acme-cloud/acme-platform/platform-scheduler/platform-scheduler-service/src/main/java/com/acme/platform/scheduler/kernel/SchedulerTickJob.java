package com.acme.platform.scheduler.kernel;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * tick 主循环调度入口（REQ-20261121-071002 确认⑤终稿）：
 * fixedDelay=1000（1s 主循环）+ ShedLock 注解单发（lockAtMostFor=5s / lockAtLeastFor=1s，
 * 锁名 platform-scheduler-tick，锁表 sch_scheduler_lock JDBC 口径——集群双实例并发 tick 仅一方执行，
 * AC-4 选主语义）。锁注解由 {@code SchedulerSchedulingConfig#@EnableSchedulerLock} 装配切面承载。
 */
@Component
public class SchedulerTickJob {

    /** tick 单发锁名（@SchedulerLock 与测试域 AC-4 锁竞争夹具单源引用，防字面量漂移）。 */
    public static final String TICK_LOCK_NAME = "platform-scheduler-tick";

    private static final Logger log = LoggerFactory.getLogger(SchedulerTickJob.class);

    private final SchedulerKernel kernel;

    public SchedulerTickJob(SchedulerKernel kernel) {
        this.kernel = kernel;
    }

    /** 每秒 tick（持有选主锁的实例执行；非持有实例仅提供 API/兜底面）。 */
    @Scheduled(fixedDelay = 1000, initialDelay = 3000)
    @SchedulerLock(name = TICK_LOCK_NAME, lockAtMostFor = "5s", lockAtLeastFor = "1s")
    public void tick() {
        try {
            kernel.tick();
        } catch (RuntimeException ex) {
            // tick 单轮异常不中断主循环（下轮继续；日志承载定位）
            log.error("tick 执行异常（下轮继续）: {}", ex.getMessage(), ex);
        }
    }
}
