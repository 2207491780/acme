package com.acme.platform.workflow.api.dto;

/**
 * 定时器触发请求（POST /internal/v1/workflow/timers/{timerJobId}/fire 回调体；确认⑥）。
 * CAS 幂等口径：wf_timer_job uk(scheduler_job_key) + 状态 PENDING→FIRED 单向迁移，
 * 重复 fire → WORKFLOW_TASK_STATE_CONFLICT 之外的实例域状态冲突（02-0002）拒绝。
 *
 * @param schedulerExecutionId 调度中心执行记录 id（可空——本地兜底队列触发不携带）
 * @param firedAt              计划触发时刻（可空，缺省=服务端当前时间）
 */
public record TimerFireRequest(String schedulerExecutionId, java.time.Instant firedAt) {
}
