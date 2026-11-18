package com.acme.framework.security.support;

/**
 * 数据范围解析扩展点（REQ-20261114-120104 五档模型，FR-017 评估器机制的 scope 侧延伸）：
 * 业务实现注册 bean，按域解析当前主体可见范围（走库或跨域网关，机制不感知）；
 * 无实现=业务未启用 scope 模型（Q-004 精神：未配置不拦截）。
 * 与 {@link DataPermissionEvaluator}（ALLOW/DENY 门禁）互补：本扩展点产出过滤条件载体，
 * 由消费方落入查询条件，不做放行/拒绝判定。
 */
public interface DataScopeResolver {

    /** 解析主体的数据范围判定（实现方异常由调用方按 fail-closed 口径处理）。 */
    DataScopeDecision resolve(DataPermissionContext context);
}
