package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门视图（REQ-20261114-120104 契约⑫钉死形状 id/parentId/deptName/sortOrder/status/children/version；
 * 树形响应 children 嵌套含禁用态，平铺响应 children 恒空；
 * 物化 path 为子树解析内部实现列，不对外暴露）。
 */
public record AdminDeptView(Long id, Long parentId, String deptName, Integer sortOrder, EnableStatus status,
                            List<AdminDeptView> children, Long version) {

    public AdminDeptView {
        children = children == null ? List.of() : new ArrayList<>(children);
    }
}
