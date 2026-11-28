package com.acme.business.leave.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * leave 域调度装配（REQ-20261128-092210 确认②③；InfraSchedulingConfig 先例同款）：启用
 * {@code @Scheduled} 承载流程启动补偿重试任务（LeaveStartRetryJob）。多实例安全由乐观锁认领
 * 承担（P2-06 单体 ShedLock 失效已知，不引入 ShedLock/framework-job 新依赖）。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class LeaveSchedulingConfig {
}
