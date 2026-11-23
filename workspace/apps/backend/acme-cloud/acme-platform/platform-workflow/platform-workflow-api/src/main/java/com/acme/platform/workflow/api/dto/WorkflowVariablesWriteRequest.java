package com.acme.platform.workflow.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 流程变量增量合并写请求（PUT /internal/v1/workflow/process-instances/{id}/variables；
 * REQ-20261122-233425 确认③：复用 VariableService upsert——uk(instance_id,name) 冲突转更新）。
 *
 * @param variables 增量键值（空 Map 忽略；null → 400 校验拒绝）
 */
public record WorkflowVariablesWriteRequest(@NotNull Map<String, Object> variables) {
}
