package com.acme.platform.workflow.api.dto;

import com.acme.platform.workflow.api.WorkflowDefinitionStatus;

import java.time.Instant;

/**
 * 流程定义视图（GET /{id} 含 bpmnXml；分页列表 bpmnXml 置空，FR-024 Long wire=字符串经
 * framework-jackson 全局 LongToStringModule 承载）。
 *
 * @param id         定义 id
 * @param key        流程键
 * @param name       流程名称
 * @param version    版本（同 key 部署自增）
 * @param status     DRAFT/DEPLOYED/DISABLED
 * @param bpmnXml    BPMN XML（仅详情返回）
 * @param layoutJson 设计器布局 json（可空列预留，BPM-2 消费）
 * @param createdAt  创建时间
 * @param deployedAt 最近部署时间（可空）
 */
public record ProcessDefinitionView(Long id, String key, String name, Integer version,
                                    WorkflowDefinitionStatus status, String bpmnXml, String layoutJson,
                                    Instant createdAt, Instant deployedAt) {
}
