package com.acme.platform.scheduler.api;

/**
 * 执行结果回调（设计规格 §4：执行完成后执行器异步上报 POST
 * /internal/v1/scheduler/executions/{executionId}/result；调度侧按确认④ CAS 推进状态）。
 * state 允许取值：RUNNING（开跑上报，可选）/ SUCCESS / FAILED（终态上报，必须）。
 * PENDING/DISPATCHED 直接上报 SUCCESS 会被 CAS 拒绝（不可跳过 RUNNING）。
 * JOB-2 执行器 SDK 对接契约，字段名不得变更。
 *
 * @param state 回调状态（RUNNING / SUCCESS / FAILED）
 * @param result 结果摘要（jsonb 文本，可空；大文本走流面归 JOB-4）
 * @param error 失败信息（state=FAILED 时必填，可空其余）
 * @param costMs 执行耗时毫秒（终态上报时建议携带）
 */
public record ExecutionResultReport(ExecutionState state, String result, String error, Long costMs) {
}
