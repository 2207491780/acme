package com.acme.framework.job.support;

import java.lang.reflect.Method;

/**
 * 调度任务元数据（REQ-20261121-200724 确认③）：{@code @JobHandler} 收集载体；
 * 执行器注册表 handlers 清单与下发执行定位共用。框架不承载具体任务实现（BR-017）。
 */
public record JobHandlerDefinition(String jobKey, long timeoutMs, boolean shardable, String description,
                                   Object target, Method method, String accessPoint) {

    public JobHandlerDefinition {
        if (jobKey == null || jobKey.isBlank()) {
            throw new IllegalArgumentException("jobKey 不能为空");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs 必须为正数: " + jobKey);
        }
    }
}
