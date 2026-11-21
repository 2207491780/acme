package com.acme.platform.scheduler.config;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度内核装配（REQ-20261121-071002 确认⑤终稿）：启用 {@code @Scheduled} 调度入口与
 * ShedLock 注解单发（@EnableSchedulerLock 缺省 lockAtMostFor 与 tick 注解值对齐 5s）。
 * 锁提供方 {@code LockProvider} 由 {@link SchedulerLockConfig} 装配（JDBC，锁表 sch_scheduler_lock）。
 * platform-infra InfraSchedulingConfig 同款形态（@EnableAsync 本服务无异步埋点面，不开启）。
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5s")
public class SchedulerSchedulingConfig {
}
