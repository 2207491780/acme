package com.acme.platform.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 启动流程实例请求（契约 API：POST /admin/v1/workflow/process-instances）。
 * 校验定义 DEPLOYED（否则 WORKFLOW_DEFINITION_NOT_DEPLOYED）；businessKey 必填
 * （WORKFLOW_BUSINESS_KEY_REQUIRED）；variables 作为实例根作用域变量（jsonb 落库）。
 *
 * @param definitionId 目标定义 id（必须 DEPLOYED）
 * @param businessKey  业务键（idx(business_key) 查询锚点）
 * @param variables    初始变量（可空）
 */
public record ProcessInstanceStartRequest(
        @NotNull Long definitionId,
        @NotBlank String businessKey,
        Map<String, Object> variables) {
}
