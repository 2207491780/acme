package com.acme.framework.job.support;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调度任务注册表（REQ-20261121-200724 确认③）：启动收集 {@code @JobHandler} 登记；
 * 重复注册显式失败（与 {@link TaskRegistry} 同口径）。本注册表是执行器面事实源——
 * 注册/心跳 handlers 清单与下发指令按 jobKey 定位均取于此。
 */
public class JobHandlerRegistry {

    private final Map<String, JobHandlerDefinition> handlers = new ConcurrentHashMap<>();

    /** 登记（jobKey 冲突显式失败）。 */
    public void register(JobHandlerDefinition definition) {
        JobHandlerDefinition existing = handlers.putIfAbsent(definition.jobKey(), definition);
        if (existing != null) {
            throw new IllegalStateException("调度任务重复注册（REQ-20261121-200724 确认③）: " + definition.jobKey());
        }
    }

    /** 查询登记。 */
    public Optional<JobHandlerDefinition> find(String jobKey) {
        return Optional.ofNullable(handlers.get(jobKey));
    }

    /** 全部登记（jobKey 稳定排序；注册/心跳 handlers 清单同源）。 */
    public List<JobHandlerDefinition> all() {
        return handlers.values().stream()
                .sorted(java.util.Comparator.comparing(JobHandlerDefinition::jobKey))
                .toList();
    }

    /** handler 键清单（执行器注册/心跳上报形态）。 */
    public List<String> keys() {
        return all().stream().map(JobHandlerDefinition::jobKey).toList();
    }

    /** 已登记数量。 */
    public int size() {
        return handlers.size();
    }
}
