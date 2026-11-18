package com.acme.platform.system.api;

import java.util.List;

/**
 * 当前主体数据范围视图（REQ-20261114-120104 服务间内部端点响应，/internal/v1/authorization/data-scope）：
 * scope 为五档枚举名（framework-security DataScope 同名 wire 契约）；deptIds 为已展开的可见部门集
 * （DEPT_AND_CHILD 含子树）；userIds 为可见主体 id 集（SELF 档=本人，部门档=部门内用户，供审计等
 * operator 维度集合过滤）。scope=ALL 时两集合为空。
 *
 * @param scope    五档枚举名（ALL/DEPT/DEPT_AND_CHILD/SELF/CUSTOM）
 * @param deptIds  可见部门 id 集（已展开；ALL 档为空集）
 * @param userIds  可见主体 id 集（字符串形态，与审计 operator_id 对齐；ALL 档为空集）
 */
public record DataScopeView(String scope, List<Long> deptIds, List<String> userIds) {

    public DataScopeView {
        deptIds = deptIds == null ? List.of() : List.copyOf(deptIds);
        userIds = userIds == null ? List.of() : List.copyOf(userIds);
    }
}
