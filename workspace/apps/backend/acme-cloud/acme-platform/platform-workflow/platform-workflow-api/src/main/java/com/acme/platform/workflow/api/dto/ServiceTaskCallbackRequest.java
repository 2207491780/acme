package com.acme.platform.workflow.api.dto;

import java.util.Map;

/**
 * ServiceTask 委托回调请求（POST /internal/v1/workflow/service-tasks/{executionId}/callback；
 * 确认⑤/AC-8）：success=true → 以 result 合并变量快照推进 execution；success=false → 按节点
 * acme:retryMaxAttempts 重试下发，耗尽 → 实例 SUSPENDED(reason=SERVICE_TASK_FAILED)。
 *
 * @param success 业务处理结果（true=成功推进）
 * @param result  成功结果（键值并入实例变量，可空；特殊键 __complete__=false 表示"未完成，等待
 *                再次回调"，用于异步长任务——下轮回调携带 success=true 推进）
 * @param error   失败描述（落历史 detail）
 */
public record ServiceTaskCallbackRequest(Boolean success, Map<String, Object> result, String error) {
}
