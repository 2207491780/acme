package com.acme.platform.workflow.api.dto;

/**
 * 任务视图（GET /tasks/todo | /tasks/done 列表项；详情形态见
 * {@link ProcessInstanceView.TaskItemView}，本类型为列表面独立别名以便前端消费）。
 * 仅展示字段投影同 TaskItemView。
 * 投影扩展（REQ-20261122-102730 确认⑤）：definitionKey/definitionName/businessKey/initiator
 * 经 instance+definition 批量装配（页级两次 in 查询，禁 N+1 逐条）；wire id 保持字符串（FR-024）。
 *
 * @param id             任务 id
 * @param instanceId     实例 id
 * @param nodeId         节点 id
 * @param nodeName       节点名称
 * @param assignee       当前办理人
 * @param status         任务状态
 * @param claimTime      认领时刻
 * @param completeTime   办结时刻
 * @param createTime     创建时刻
 * @param definitionKey  定义键（instance→definition 投影，缺失为空）
 * @param definitionName 定义名称（同上）
 * @param businessKey    业务键（实例投影）
 * @param initiator      发起人（用户 id，实例投影）
 */
public record TaskView(Long id, Long instanceId, String nodeId, String nodeName, String assignee,
                       com.acme.platform.workflow.api.WorkflowTaskStatus status, java.time.Instant claimTime,
                       java.time.Instant completeTime, java.time.Instant createTime,
                       String definitionKey, String definitionName, String businessKey, String initiator) {
}
