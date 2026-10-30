package com.acme.platform.system.api;

/**
 * 平台权限点编码常量（FR-008、架构设计 5.2）：framework 侧不硬编码角色与权限点（Backend.md 8.2.1），
 * 编码仅存在于数据（种子脚本）与代码常量（本类）；内置超管角色经数据绑定全量权限点（等价短路）。
 * 清单与 openapi.json x-permission 标注一一对应；新增能力同步增补并登记种子脚本。
 */
public final class SystemPermissions {

    /** 凭据管理（认证域 Admin 端点，权限目录平台级统一登记）。 */
    public static final String AUTH_CREDENTIAL_MANAGE = "auth:credential:manage";

    /** 用户管理。 */
    public static final String USER_LIST = "system:user:list";
    public static final String USER_CREATE = "system:user:create";
    public static final String USER_QUERY = "system:user:query";
    public static final String USER_UPDATE = "system:user:update";
    public static final String USER_DELETE = "system:user:delete";
    public static final String USER_ASSIGN_ROLE = "system:user:assign-role";

    /** 角色管理。 */
    public static final String ROLE_LIST = "system:role:list";
    public static final String ROLE_CREATE = "system:role:create";
    public static final String ROLE_QUERY = "system:role:query";
    public static final String ROLE_UPDATE = "system:role:update";
    public static final String ROLE_DELETE = "system:role:delete";
    public static final String ROLE_GRANT = "system:role:grant";

    /** 权限点管理。 */
    public static final String PERMISSION_LIST = "system:permission:list";
    public static final String PERMISSION_CREATE = "system:permission:create";
    public static final String PERMISSION_QUERY = "system:permission:query";
    public static final String PERMISSION_UPDATE = "system:permission:update";
    public static final String PERMISSION_DELETE = "system:permission:delete";

    /** 菜单管理。 */
    public static final String MENU_LIST = "system:menu:list";
    public static final String MENU_CREATE = "system:menu:create";
    public static final String MENU_QUERY = "system:menu:query";
    public static final String MENU_UPDATE = "system:menu:update";
    public static final String MENU_DELETE = "system:menu:delete";

    /** 字典管理。 */
    public static final String DICT_LIST = "system:dict:list";
    public static final String DICT_CREATE = "system:dict:create";
    public static final String DICT_UPDATE = "system:dict:update";
    public static final String DICT_DELETE = "system:dict:delete";

    /** 租户管理。 */
    public static final String TENANT_LIST = "system:tenant:list";
    public static final String TENANT_CREATE = "system:tenant:create";
    public static final String TENANT_QUERY = "system:tenant:query";
    public static final String TENANT_UPDATE = "system:tenant:update";

    /** 审计事件查询（infra 域 Admin 端点，权限目录平台级统一登记；platform-infra-audit 增补）。 */
    public static final String INFRA_AUDIT_LIST = "infra:audit:list";

    /** 全租户审计视图（数据权限：无此权限点仅本人视图；consumer-auth-refresh-dataperm）。 */
    public static final String INFRA_AUDIT_LIST_ALL = "infra:audit:list:all";

    private SystemPermissions() {
    }
}
