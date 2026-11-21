package com.acme.platform.scheduler.application;

import com.acme.platform.scheduler.api.SchedulerJobStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 任务启停请求体（确认②：/status 专用端点；platform-system AdminStatusUpdate 同款 wire 形态）。
 *
 * @param status 目标启停状态
 */
public record JobStatusUpdateRequest(@NotNull SchedulerJobStatus status) {
}
