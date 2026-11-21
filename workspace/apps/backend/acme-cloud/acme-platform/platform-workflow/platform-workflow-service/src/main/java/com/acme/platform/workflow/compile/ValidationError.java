package com.acme.platform.workflow.compile;

/**
 * 校验错误条目（AC-2：非法 XML 部署 → VALIDATION_FAILED 逐条错误；节点定位 id 缺失时用 "-"）。
 *
 * @param nodeId  关联节点/连线 id（可空）
 * @param rule    校验规则标识（设计规格 §5.4 清单锚点）
 * @param message 面向用户的错误描述
 */
public record ValidationError(String nodeId, String rule, String message) {

    /** 校验规则标识（§5.4 全清单）。 */
    public static final String RULE_START_END_EXACTLY_ONE = "START_END_EXACTLY_ONE";
    public static final String RULE_DANGLING_FLOW = "DANGLING_FLOW";
    public static final String RULE_NODE_ID_UNIQUE = "NODE_ID_UNIQUE";
    public static final String RULE_GATEWAY_CONDITIONS = "GATEWAY_CONDITIONS";
    public static final String RULE_USER_TASK_ASSIGNMENT = "USER_TASK_ASSIGNMENT";
    public static final String RULE_SERVICE_TASK_CONFIG = "SERVICE_TASK_CONFIG";
    public static final String RULE_BOUNDARY_ATTACHMENT = "BOUNDARY_ATTACHMENT";
    public static final String RULE_EXPRESSION_SYNTAX = "EXPRESSION_SYNTAX";
    public static final String RULE_UNSUPPORTED_ELEMENT = "UNSUPPORTED_ELEMENT";
}
