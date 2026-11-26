package com.acme.platform.system.api.dto;

import java.util.List;

/**
 * 用户表单目录视图（REQ-20261125-230807，GET /admin/v1/system/user-form-options）：
 * 用户管理页筛选 / 表单 / 列名称映射的目录一次拉全——deptTree=全量部门树（含禁用态）、
 * positions / roles=全量平铺（经用例仓储全查，规模受控）。权限挂 system:user:list 既有码
 * （无新权限点种子），替代该页对 system:dept:list / system:position:list / 角色分页接口的依赖。
 */
public record UserFormOptionsView(List<AdminDeptView> deptTree,
                                  List<AdminPositionView> positions,
                                  List<AdminRoleView> roles) {

    public UserFormOptionsView {
        deptTree = deptTree == null ? List.of() : List.copyOf(deptTree);
        positions = positions == null ? List.of() : List.copyOf(positions);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
