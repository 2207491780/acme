package com.acme.platform.workflow.api.dto;

import com.acme.platform.workflow.api.WorkflowInstanceStatus;
import com.acme.platform.workflow.api.WorkflowTaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 流程实例视图（契约 API：GET /{id} = 实例 + 活跃节点 + 任务树 + 变量摘要）。
 *
 * @param id            实例 id
 * @param definitionId  定义 id
 * @param definitionKey 定义键
 * @param businessKey   业务键
 * @param initiator     发起人（用户 id）
 * @param status        实例状态
 * @param suspendReason 挂起原因（可空）
 * @param startTime     启动时刻
 * @param endTime       结束时刻（可空）
 * @param variables     变量摘要（根作用域当前值）
 * @param activeNodes   活跃节点（is_active execution 投影：节点 id/类型/是否并发/是否 scope 根）
 * @param tasks         活跃任务树（CREATED/CLAIMED 状态，TaskItemView 形态）
 */
public record ProcessInstanceView(Long id, Long definitionId, String definitionKey, String businessKey,
                                  String initiator, WorkflowInstanceStatus status, String suspendReason,
                                  Instant startTime, Instant endTime, Map<String, Object> variables,
                                  List<ActiveNodeView> activeNodes, List<TaskItemView> tasks) {

    /**
     * 活跃节点投影（execution is_active 行）。
     *
     * @param executionId  执行 id
     * @param parentNodeId 父执行 id（可空=实例根）
     * @param nodeId       停留节点 id
     * @param nodeType     节点 BPMN 类型名
     * @param concurrent   并发分支标记
     * @param scopeRoot    子流程 scope 根标记
     */
    public record ActiveNodeView(Long executionId, Long parentNodeId, String nodeId, String nodeType,
                                 Boolean concurrent, Boolean scopeRoot) {
    }

    /**
     * 任务投影（todo/done/实例详情共用）。
     *
     * @param id           任务 id
     * @param instanceId   实例 id
     * @param executionId  执行 id
     * @param nodeId       节点 id
     * @param nodeName     节点名称
     * @param assignee     当前办理人（未认领=空）
     * @param status       任务状态
     * @param formData     表单数据（完成态回显）
     * @param dueDate      截止时间（可空）
     * @param claimTime    认领时刻（可空）
     * @param completeTime 办结时刻（可空）
     * @param createTime   创建时刻
     */
    public record TaskItemView(Long id, Long instanceId, Long executionId, String nodeId, String nodeName,
                               String assignee, WorkflowTaskStatus status, Map<String, Object> formData,
                               Instant dueDate, Instant claimTime, Instant completeTime, Instant createTime) {
    }
}
