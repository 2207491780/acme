package com.acme.platform.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务调度装配（REQ-20261114-071607）：启用 {@code @Scheduled} 调度入口；
 * 互斥执行链（@AcmeTask 切面 + ShedLock）由 framework-job 装配承担。
 */
@Configuration
@EnableScheduling
public class InfraSchedulingConfig {
}
