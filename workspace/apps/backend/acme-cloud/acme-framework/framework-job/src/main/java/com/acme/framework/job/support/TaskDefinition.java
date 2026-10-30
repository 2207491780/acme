package com.acme.framework.job.support;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 任务元数据（FR-026）：注册表登记面；框架不承载具体任务实现（BR-017）。
 */
public record TaskDefinition(String taskId, MutexMode mutexMode,
                             Duration lockAtMostFor, Duration lockAtLeastFor,
                             Object target, Method method, String accessPoint) {

    public TaskDefinition {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        mutexMode = mutexMode == null ? MutexMode.GLOBAL : mutexMode;
    }
}
