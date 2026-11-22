package com.acme.platform.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存流程定义 XML 请求（契约 API：PUT /admin/v1/workflow/process-definitions/{id}/xml，草稿态）。
 * 部署态定义保存 XML 被拒绝（WORKFLOW_DEFINITION_DISABLED 口径外，直接 409 STATE_CONFLICT 语义
 * 归 01 段 DISABLED）——版本演进走「同 key 新草稿部署自增 version」。
 *
 * @param bpmnXml    标准 BPMN 2.0 XML（namespace http://www.omg.org/spec/BPMN/20100524/MODEL）
 * @param layoutJson 设计器布局 json 原文透传（REQ-20261122-023441 确认⑤，可选；随 XML 整体覆盖
 *                   落库 layout_json——缺省 null 即置 NULL，前端保存总是带布局或显式 null）
 */
public record ProcessDefinitionXmlSaveRequest(@NotBlank String bpmnXml,
                                              @Size(max = 262144) String layoutJson) {
}
