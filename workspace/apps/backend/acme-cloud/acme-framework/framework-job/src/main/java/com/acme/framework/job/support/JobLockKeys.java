package com.acme.framework.job.support;

import com.acme.framework.core.support.KeyComposer;

/**
 * 任务锁 key 规范（FR-029/AC-027、Q-006）：{@code {服务}:j:{taskId}[:t:{tenantId}]}（Backend.md 12.3.2 服务前缀）；
 * 经 core {@link KeyComposer}（Modules.md 4.3）。
 */
public final class JobLockKeys {

    private static final String SEGMENT = "j";

    private final KeyComposer keyComposer;

    public JobLockKeys(KeyComposer keyComposer) {
        this.keyComposer = keyComposer;
    }

    /**
     * 构造锁 key：tenantId 为空按全局互斥（不带租户段，FR-029 异常分支）。
     */
    public String build(String taskId, String tenantIdOrNull) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (tenantIdOrNull == null || tenantIdOrNull.isBlank()) {
            return keyComposer.compose(SEGMENT, taskId);
        }
        return keyComposer.compose(SEGMENT, taskId, "t", tenantIdOrNull.trim());
    }
}
