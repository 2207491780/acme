package com.acme.platform.system.api;

/**
 * 菜单节点类型（FR-008、Q-005 裁定 B：目录 + 菜单两级；按钮级不在本期）。
 */
public enum MenuType {

    /** 目录（容器节点）。 */
    DIRECTORY,

    /** 菜单（叶子页面）。 */
    MENU
}
