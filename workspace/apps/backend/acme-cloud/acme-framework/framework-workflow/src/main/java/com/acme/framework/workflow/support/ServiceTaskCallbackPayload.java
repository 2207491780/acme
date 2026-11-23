package com.acme.framework.workflow.support;

import java.util.Map;

/**
 * ServiceTask 委托回调请求体（SDK 自动回调 → POST /internal/v1/workflow/service-tasks/{executionId}/callback）。
 *
 * <p>wire 契约事实源=platform-workflow-api {@code ServiceTaskCallbackRequest}（字段名镜像，
 * 同 {@link ServiceTaskDispatchPayload} 消费侧解码口径）：success=true → result 并入变量后推进；
 * 特殊键 {@code __complete__=false}=未完成等待再次回调（透传）。</p>
 *
 * @param success 业务处理结果（SDK 自动回调恒 true——handler 抛出异常时不回调，交引擎重试/挂起语义）
 * @param result  handler 结果变量（可空；并入实例变量）
 * @param error   失败描述（成功回调无值）
 */
public record ServiceTaskCallbackPayload(Boolean success, Map<String, Object> result, String error) {
}
