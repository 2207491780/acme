package com.acme.platform.system.api;

/**
 * 菜单节点类型（FR-008、Q-005 裁定 B：目录 + 菜单两级；REQ-20261119-051119 确认①增补按钮级）。
 */
public enum MenuType {

    /** 目录（容器节点）。 */
    DIRECTORY,

    /** 菜单（叶子页面）。 */
    MENU,

    /**
     * 按钮（REQ-20261119-051119 确认①：API 权限点的授权管理投影）——permission_code 必须挂
     * 既有 API 权限码（不新增权限点），path 恒空（不可导航，导航构建剔除）；仅用于角色授权菜单树
     * 的按钮级勾选，勾选经 RoleUseCase.bindMenus 联动同步 role_permission（确认②）。
     */
    BUTTON
}
