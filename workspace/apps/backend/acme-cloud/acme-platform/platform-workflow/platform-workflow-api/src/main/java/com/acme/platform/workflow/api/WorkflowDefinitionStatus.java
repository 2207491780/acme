package com.acme.platform.workflow.api;

/**
 * 流程定义状态（契约确认②终稿：DRAFT 创建草稿 → DEPLOYED 部署生效 → DISABLED 禁用）。
 * DISABLED：新实例拒绝、运行实例不受影响；编译缓存随禁用失效（AC-13）。
 */
public enum WorkflowDefinitionStatus {

    DRAFT,
    DEPLOYED,
    DISABLED
}
