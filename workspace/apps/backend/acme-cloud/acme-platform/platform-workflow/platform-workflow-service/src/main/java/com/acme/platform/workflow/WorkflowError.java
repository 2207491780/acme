package com.acme.platform.workflow;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.workflow.api.WorkflowErrorCodes;

/**
 * BPM 工作流域错误码枚举（10.5.2 三段式，serviceId=platform-workflow；REQ-20261121-142316
 * 确认⑨终稿）。码值与 platform-workflow-api WorkflowErrorCodes 常量一一对应（跨服务判定取
 * 常量、本服务抛枚举）。
 */
public enum WorkflowError implements ErrorCode, HttpStatusAware {

    // ---- 01 段：流程定义域 ----

    /** 定义键已存在（创建草稿撞既有 key）。 */
    WORKFLOW_KEY_DUPLICATE(WorkflowErrorCodes.WORKFLOW_KEY_DUPLICATE, "流程键已存在", 409),

    /** BPMN XML 结构非法（DOM 解析失败：非 XML / 非 BPMN 2.0 process）。 */
    WORKFLOW_XML_INVALID(WorkflowErrorCodes.WORKFLOW_XML_INVALID, "BPMN XML 结构非法", 400),

    /** 结构校验未通过（部署前置校验；逐条错误明细随 Result.data 返回）。 */
    WORKFLOW_VALIDATION_FAILED(WorkflowErrorCodes.WORKFLOW_VALIDATION_FAILED, "流程结构校验未通过", 409),

    /** 流程定义不存在。 */
    WORKFLOW_DEFINITION_NOT_FOUND(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND, "流程定义不存在", 404),

    /** 定义已禁用（草稿保存 XML / 再部署 / 启动对 DISABLED 定义）。 */
    WORKFLOW_DEFINITION_DISABLED(WorkflowErrorCodes.WORKFLOW_DEFINITION_DISABLED, "流程定义已禁用", 409),

    // ---- 02 段：流程实例域 ----

    /** 流程实例不存在。 */
    WORKFLOW_INSTANCE_NOT_FOUND(WorkflowErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, "流程实例不存在", 404),

    /** 实例状态跃迁冲突（终态再操作 / SUSPENDED 态受理 / resume 对非挂起实例 / 重复 timer fire）。 */
    WORKFLOW_INSTANCE_STATE_CONFLICT(WorkflowErrorCodes.WORKFLOW_INSTANCE_STATE_CONFLICT,
            "流程实例状态跃迁冲突", 409),

    /** 定义未部署（启动对 DRAFT/DISABLED 定义）。 */
    WORKFLOW_DEFINITION_NOT_DEPLOYED(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_DEPLOYED,
            "流程定义未部署或已禁用", 409),

    /** 业务键必填（启动请求缺 businessKey）。 */
    WORKFLOW_BUSINESS_KEY_REQUIRED(WorkflowErrorCodes.WORKFLOW_BUSINESS_KEY_REQUIRED, "业务键不能为空", 400),

    // ---- 03 段：任务域 ----

    /** 任务不存在。 */
    WORKFLOW_TASK_NOT_FOUND(WorkflowErrorCodes.WORKFLOW_TASK_NOT_FOUND, "任务不存在", 404),

    /** 任务状态跃迁冲突（未认领 unclaim / 已认领再 claim / 终态任务再操作）。 */
    WORKFLOW_TASK_STATE_CONFLICT(WorkflowErrorCodes.WORKFLOW_TASK_STATE_CONFLICT, "任务状态跃迁冲突", 409),

    /** 操作人与任务归属不匹配（非 assignee complete / 非候选 claim）。 */
    WORKFLOW_ASSIGNEE_MISMATCH(WorkflowErrorCodes.WORKFLOW_ASSIGNEE_MISMATCH, "无权操作该任务", 403),

    /** 委托状态非法（单层委托链已用尽再 delegate / 委托自身）。 */
    WORKFLOW_DELEGATE_STATE_INVALID(WorkflowErrorCodes.WORKFLOW_DELEGATE_STATE_INVALID, "任务委派状态非法", 409),

    /** 候选解析为空（UserTask 指派规则无法落定任何人，实例 SUSPENDED 承载）。 */
    WORKFLOW_NO_ASSIGNEE_RESOLVED(WorkflowErrorCodes.WORKFLOW_NO_ASSIGNEE_RESOLVED, "任务无可用候选人", 409);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    WorkflowError(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
