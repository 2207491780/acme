package com.acme.platform.workflow.api.dto;

/**
 * 任务驳回请求（POST /tasks/{taskId}/reject；确认⑤语义终稿）：
 * targetNodeId 缺省=回退到上一个已完成 UserTask（无历史 UserTask 时回退 StartEvent 发起新一轮）；
 * 指定=回退到任意历史 UserTask/StartEvent。驳回产生新任务实例、历史链保留并记 REJECTED 边。
 *
 * @param targetNodeId 回退目标节点 id（可空=缺省回退）
 * @param comment      驳回意见（落历史 detail）
 */
public record TaskRejectRequest(String targetNodeId, String comment) {
}
