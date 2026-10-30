package com.acme.framework.security.support;

/**
 * 数据权限评估扩展点（FR-017、7.5）：业务实现注册 bean；无评估器=未配置业务数据权限规则→放行（Q-004）；
 * 评估器异常→按拒绝处理（FR-017 异常分支）。
 */
public interface DataPermissionEvaluator {

    /** 评估数据权限（FR-017）。 */
    DataPermissionDecision evaluate(DataPermissionContext context);
}
