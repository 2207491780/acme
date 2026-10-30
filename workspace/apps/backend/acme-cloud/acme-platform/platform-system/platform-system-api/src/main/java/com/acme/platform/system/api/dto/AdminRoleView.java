package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色视图（11.5.1 Admin* 前缀；根节点 parentId=0。树形响应 children 嵌套，平铺响应 children 恒空）。
 */
public record AdminRoleView(Long id, Long parentId, String roleCode, String roleName, EnableStatus status,
                            Boolean builtIn, String remark, Instant createdAt, List<AdminRoleView> children,
                            Long version) {

    public AdminRoleView {
        children = children == null ? List.of() : new ArrayList<>(children);
    }
}
