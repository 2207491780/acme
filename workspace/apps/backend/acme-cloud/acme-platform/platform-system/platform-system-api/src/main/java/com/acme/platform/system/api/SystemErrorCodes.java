package com.acme.platform.system.api;

/**
 * 系统管理域错误码文本常量（Backend.md 10.5.2 三段式，serviceId=platform-system、模块段自管）：
 * 供跨服务消费方（如 platform-auth 登录链路）按码判定；完整枚举（含 HTTP 映射）归
 * platform-system-service SystemError，两处取值一致由实现说明与单测锁定。
 */
public final class SystemErrorCodes {

    /** 用户不存在（404）。 */
    public static final String USER_NOT_FOUND = "platform-system-01-0001";

    /** 用户名已存在（409）。 */
    public static final String USERNAME_DUPLICATED = "platform-system-01-0002";

    /** 用户删除被拒绝（内置超管 / 操作者本人，409）。 */
    public static final String USER_DELETE_DENIED = "platform-system-01-0003";

    /** 角色不存在（404）。 */
    public static final String ROLE_NOT_FOUND = "platform-system-02-0001";

    /** 租户内角色编码已存在（409）。 */
    public static final String ROLE_CODE_DUPLICATED = "platform-system-02-0002";

    /** 角色删除被拒绝（内置 / 存在子角色 / 存在用户绑定，409）。 */
    public static final String ROLE_DELETE_DENIED = "platform-system-02-0003";

    /** 角色父节点调整成环（目标父为自身或其子孙，409）。 */
    public static final String ROLE_PARENT_CYCLE = "platform-system-02-0004";

    /** 内置角色不允许调整父角色（409）。 */
    public static final String ROLE_REPARENT_DENIED = "platform-system-02-0005";

    /** 权限点不存在（404）。 */
    public static final String PERMISSION_NOT_FOUND = "platform-system-03-0001";

    /** 权限点编码已存在（409）。 */
    public static final String PERMISSION_CODE_DUPLICATED = "platform-system-03-0002";

    /** 权限点删除被拒绝（存在角色绑定，409）。 */
    public static final String PERMISSION_DELETE_DENIED = "platform-system-03-0003";

    /** 菜单不存在（404）。 */
    public static final String MENU_NOT_FOUND = "platform-system-04-0001";

    /** 菜单删除被拒绝（存在子菜单 / 角色绑定，409）。 */
    public static final String MENU_DELETE_DENIED = "platform-system-04-0002";

    /** 字典类型不存在（404）。 */
    public static final String DICT_TYPE_NOT_FOUND = "platform-system-05-0001";

    /** 租户内字典编码已存在（409）。 */
    public static final String DICT_CODE_DUPLICATED = "platform-system-05-0002";

    /** 同类型下字典项值已存在（409）。 */
    public static final String DICT_ITEM_DUPLICATED = "platform-system-05-0003";

    /** 字典类型删除被拒绝（存在字典项，409）。 */
    public static final String DICT_TYPE_DELETE_DENIED = "platform-system-05-0004";

    /** 租户不存在（404）。 */
    public static final String TENANT_NOT_FOUND = "platform-system-06-0001";

    /** 租户编码已存在（409）。 */
    public static final String TENANT_CODE_DUPLICATED = "platform-system-06-0002";

    /** 默认租户停用被拒绝（409）。 */
    public static final String TENANT_DISABLE_DENIED = "platform-system-06-0003";

    /** 接口级权限点不足（403）。 */
    public static final String PERMISSION_DENIED = "platform-system-07-0001";

    private SystemErrorCodes() {
    }
}
