package com.acme.platform.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建流程定义草稿请求（契约 API：POST /admin/v1/workflow/process-definitions）。
 * key 全局唯一（撞键 → platform-workflow-01-0001）；bpmnXml 可空（先建草稿后存 XML）。
 *
 * @param key     流程键（全局唯一，同 key 部署自增 version）
 * @param name    流程名称
 * @param bpmnXml BPMN 2.0 XML（可空，草稿态补存）
 */
public record ProcessDefinitionCreateRequest(
        @NotBlank @Size(max = 64) String key,
        @NotBlank @Size(max = 128) String name,
        String bpmnXml) {
}
