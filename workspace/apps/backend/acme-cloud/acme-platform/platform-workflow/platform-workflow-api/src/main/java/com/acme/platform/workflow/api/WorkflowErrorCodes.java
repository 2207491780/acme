package com.acme.platform.workflow.api;

/**
 * BPM 工作流域错误码文本常量（Backend.md 10.5.2 三段式，serviceId=platform-workflow、模块段自管；
 * REQ-20261121-142316 确认⑨终稿）：供跨服务消费方按码判定；完整枚举（含 HTTP 映射）归
 * platform-workflow-service WorkflowError，两处取值一致由实现说明与单测锁定。
 */
public final class WorkflowErrorCodes {

    // ---- 01 段：流程定义域 ----

    /** 定义键已存在（创建草稿撞既有 key，409）。 */
    public static final String WORKFLOW_KEY_DUPLICATE = "platform-workflow-01-0001";

    /** BPMN XML 结构非法（DOM 解析失败：非 XML / 非 BPMN 2.0 process，400）。 */
    public static final String WORKFLOW_XML_INVALID = "platform-workflow-01-0002";

    /** 结构校验未通过（部署前置校验；逐条错误明细随 Result.data 返回，409）。 */
    public static final String WORKFLOW_VALIDATION_FAILED = "platform-workflow-01-0003";

    /** 流程定义不存在（详情/保存 XML/部署/禁用对未知 id，404）。 */
    public static final String WORKFLOW_DEFINITION_NOT_FOUND = "platform-workflow-01-0004";

    /** 定义已禁用（草稿保存 XML/再部署对 DISABLED 定义，409）。 */
    public static final String WORKFLOW_DEFINITION_DISABLED = "platform-workflow-01-0005";

    // ---- 02 段：流程实例域 ----

    /** 流程实例不存在（详情/终止/挂起/恢复/历史对未知 id，404）。 */
    public static final String WORKFLOW_INSTANCE_NOT_FOUND = "platform-workflow-02-0001";

    /** 实例状态跃迁冲突（终态再操作 / SUSPENDED 态受理任务操作 / resume 对非挂起实例，409）。 */
    public static final String WORKFLOW_INSTANCE_STATE_CONFLICT = "platform-workflow-02-0002";

    /** 定义未部署（启动对 DRAFT/DISABLED 定义，409）。 */
    public static final String WORKFLOW_DEFINITION_NOT_DEPLOYED = "platform-workflow-02-0003";

    /** 业务键必填（启动请求缺 businessKey，400）。 */
    public static final String WORKFLOW_BUSINESS_KEY_REQUIRED = "platform-workflow-02-0004";

    // ---- 03 段：任务域 ----

    /** 任务不存在（complete/reject/claim 等对未知 taskId，404）。 */
    public static final String WORKFLOW_TASK_NOT_FOUND = "platform-workflow-03-0001";

    /** 任务状态跃迁冲突（未认领 unclaim / 已认领再 claim / 终态任务再操作，409）。 */
    public static final String WORKFLOW_TASK_STATE_CONFLICT = "platform-workflow-03-0002";

    /** 操作人与任务归属不匹配（非 assignee complete / 非候选 claim，403）。 */
    public static final String WORKFLOW_ASSIGNEE_MISMATCH = "platform-workflow-03-0003";

    /** 委托状态非法（单层委托链已用尽再 delegate / 委托自身，409）。 */
    public static final String WORKFLOW_DELEGATE_STATE_INVALID = "platform-workflow-03-0004";

    /** 候选解析为空（UserTask 指派规则无法落定任何人，实例 SUSPENDED 承载，409）。 */
    public static final String WORKFLOW_NO_ASSIGNEE_RESOLVED = "platform-workflow-03-0005";

    private WorkflowErrorCodes() {
    }
}
