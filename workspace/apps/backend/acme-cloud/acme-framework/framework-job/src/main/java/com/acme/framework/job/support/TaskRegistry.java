package com.acme.framework.job.support;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务注册表（FR-026/AC-026）：启动收集 {@code @AcmeTask} 登记元数据；重复注册显式失败（FR-026 异常分支）。
 */
public class TaskRegistry {

    private final Map<String, TaskDefinition> tasks = new ConcurrentHashMap<>();

    /** 登记（taskId 冲突显式失败）。 */
    public void register(TaskDefinition definition) {
        TaskDefinition existing = tasks.putIfAbsent(definition.taskId(), definition);
        if (existing != null) {
            throw new IllegalStateException("任务重复注册（FR-026）: " + definition.taskId());
        }
    }

    /** 查询登记。 */
    public Optional<TaskDefinition> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** 全部登记。 */
    public Collection<TaskDefinition> all() {
        return tasks.values();
    }
}
