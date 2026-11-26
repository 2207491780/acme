package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色视图（11.5.1 Admin* 前缀；树形响应 children 嵌套，平铺响应 children 恒空；
 * dataScope 为 REQ-20261114-120104 契约⑫增补：五档枚举名，供角色页数据范围弹窗回显）。
 * REQ-20261125-230807（F7，对齐 REQ-20261124-213008 §API）：根角色 parentId wire 归一 null
 * （0 仅落库哨兵，响应侧 0→null）；写入侧三态语义（null 不调整 / 0 移为根 / >0 改父）见 AdminRoleUpdate。
 */
public record AdminRoleView(Long id, Long parentId, String roleCode, String roleName, EnableStatus status,
                            Boolean builtIn, String remark, String dataScope, Instant createdAt,
                            List<AdminRoleView> children, Long version) {

    public AdminRoleView {
        children = children == null ? List.of() : new ArrayList<>(children);
    }
}
