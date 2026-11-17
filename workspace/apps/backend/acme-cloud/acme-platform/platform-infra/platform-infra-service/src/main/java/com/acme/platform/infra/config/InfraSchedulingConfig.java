package com.acme.platform.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务调度与异步装配（REQ-20261114-071607 / REQ-20261117-061017）：启用 {@code @Scheduled} 调度入口
 * 与 {@code @Async} 异步执行（异常日志收集器对齐 platform-auth LoginLogRecorder 异步口径，
 * 经代理走缺省 applicationTaskExecutor）；互斥执行链（@AcmeTask 切面 + ShedLock）由 framework-job 装配承担。
 */
@Configuration
@EnableScheduling
@EnableAsync
public class InfraSchedulingConfig {
}
