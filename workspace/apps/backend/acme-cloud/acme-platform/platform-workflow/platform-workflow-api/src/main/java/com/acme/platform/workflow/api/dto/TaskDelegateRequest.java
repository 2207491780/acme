package com.acme.platform.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 任务委派请求（POST /tasks/{taskId}/delegate；确认⑤：一期=转办+owner 记录，委托链单层）。
 * owner 记录落 wf_history_activity detail（action=DELEGATE、taskId/fromUser/toUser）；
 * 已委托任务再委派 → WORKFLOW_DELEGATE_STATE_INVALID（409）。
 */
public record TaskDelegateRequest(@NotBlank String assignee) {
}
