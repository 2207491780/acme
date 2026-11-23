package com.acme.platform.workflow.api.dto;

import com.acme.platform.workflow.api.WorkflowInstanceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 流程实例运行时详情（GET /internal/v1/workflow/process-instances/{id}；REQ-20261122-233425
 * 确认③：实例 + 变量 Map + 活动任务快照 List<TaskView>——跨服务消费面的运行时投影，
 * 与 Admin 面 ProcessInstanceView（活跃节点/任务树/表单）区分：internal 只给程序消费字段）。
 *
 * @param id            实例 id
 * @param definitionId  定义 id
 * @param definitionKey 定义键
 * @param businessKey   业务键
 * @param initiator     发起人（用户 id）
 * @param status        实例状态
 * @param startTime     启动时刻
 * @param endTime       结束时刻（可空=运行中）
 * @param variables     变量快照（根作用域当前值）
 * @param activeTasks   活动任务快照（CREATED/CLAIMED；TaskView 投影）
 */
public record WorkflowInstanceDetailView(Long id, Long definitionId, String definitionKey,
                                         String businessKey, String initiator,
                                         WorkflowInstanceStatus status,
                                         Instant startTime, Instant endTime,
                                         Map<String, Object> variables,
                                         List<TaskView> activeTasks) {
}
