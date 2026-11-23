package com.acme.platform.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * internal 启动流程实例请求（POST /internal/v1/workflow/process-instances；REQ-20261122-233425
 * 确认③：业务服务面启动入口——definitionId+businessKey+variables+initiatorUserId，业务身份
 * sub 透传审计）。与 Admin 面 POST /process-instances 的差异：无 @RequirePermission 权限面
 * （internal 面走 X-Acme-Token 服务主体），initiator 显式入参（Admin 面取 UserContext）。
 *
 * @param definitionId    目标定义 id（必须 DEPLOYED，同 Admin 口径校验）
 * @param businessKey     业务键（必填；业务对账/事件回写锚点）
 * @param variables       初始变量（可空）
 * @param initiatorUserId 发起人用户 id（必填；业务调用方的真实用户主体）
 */
public record InternalProcessInstanceStartRequest(
        @NotNull Long definitionId,
        @NotBlank String businessKey,
        Map<String, Object> variables,
        @NotBlank String initiatorUserId) {
}
