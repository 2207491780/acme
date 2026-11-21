package com.acme.platform.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 任务转办请求（POST /tasks/{taskId}/transfer）：重设 assignee（状态 CLAIMED）。
 */
public record TaskTransferRequest(@NotBlank String assignee) {
}
