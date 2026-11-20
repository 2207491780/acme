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

    /** 角色编码为平台保留编码，任何租户不得自建（409）。 */
    public static final String ROLE_CODE_RESERVED = "platform-system-02-0006";

    /** 非内置角色不得绑定平台保留域权限点（409）。 */
    public static final String ROLE_GRANT_RESERVED = "platform-system-02-0007";

    /** 数据范围设置非法（档位枚举未知 / CUSTOM 未传部门集或部门集含未知部门，REQ-20261114-120104，409）。 */
    public static final String DATA_SCOPE_INVALID = "platform-system-02-0008";

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

    /** 公告已发布，不允许修改或删除（409；REQ-20261114-120102 通知公告域）。 */
    public static final String NOTICE_PUBLISHED = "platform-system-08-0001";

    /** 公告不存在（404；REQ-20261114-120102，实施增补：publish/update 对不存在 id 不可静默成功）。 */
    public static final String NOTICE_NOT_FOUND = "platform-system-08-0002";

    /** 站内信不存在（404；REQ-20261114-120102 站内信域）。 */
    public static final String MESSAGE_NOT_FOUND = "platform-system-09-0001";

    /** 无权操作他人站内信（403；REQ-20261114-120102）。 */
    public static final String MESSAGE_FORBIDDEN = "platform-system-09-0002";

    /** 部门不存在（404；REQ-20261114-120104 部门域，实施增补：update 对不存在 id 不可静默成功）。 */
    public static final String DEPT_NOT_FOUND = "platform-system-10-0001";

    /** 部门删除被拒绝（存在子部门，409；REQ-20261114-120104 确认记录③）。 */
    public static final String DEPT_HAS_CHILDREN = "platform-system-10-0002";

    /** 部门删除被拒绝（存在用户归属，409；REQ-20261114-120104 确认记录③）。 */
    public static final String DEPT_HAS_USERS = "platform-system-10-0003";

    /** 部门父节点调整成环（目标父为自身或其子孙，409；REQ-20261114-120104 实施增补）。 */
    public static final String DEPT_PARENT_CYCLE = "platform-system-10-0004";

    /** 岗位不存在（404；REQ-20261114-120104 岗位域，实施增补：update 对不存在 id 不可静默成功）。 */
    public static final String POSITION_NOT_FOUND = "platform-system-11-0001";

    /** 岗位删除被拒绝（存在用户引用，409；REQ-20261114-120104）。 */
    public static final String POSITION_IN_USE = "platform-system-11-0002";

    /** 租户内岗位编码已存在（409；REQ-20261114-120104 实施增补：(tenant_id, position_code) 唯一对齐字典先例）。 */
    public static final String POSITION_CODE_DUPLICATED = "platform-system-11-0003";

    /** 系统配置闲置锁屏时长取值非法（409；REQ-20261116-230354，取值集 {0,60000,300000,900000}）。 */
    public static final String SYSTEM_CONFIG_INVALID = "platform-system-12-0001";

    /** 水印模板含未知占位符（409；REQ-20261117-180000，合法集 {username,nickname,userId,tenantCode,deptName,date,time}）。 */
    public static final String SYSTEM_WATERMARK_TEMPLATE_INVALID = "platform-system-12-0002";

    /** 个人资料非法（400；REQ-20261119-082531 确认记录①：avatar_url 非 http(s)/相对地址等自助资料值域违规）。 */
    public static final String PROFILE_INVALID = "platform-system-13-0001";

    /** 套餐名称已存在（409；REQ-20261121-022910 租户套餐域）。 */
    public static final String TENANT_PACKAGE_DUPLICATE = "platform-system-14-0001";

    /** 套餐不存在（404；REQ-20261121-022910）。 */
    public static final String TENANT_PACKAGE_NOT_FOUND = "platform-system-14-0002";

    /** 套餐被租户绑定，删除被拒绝（409；REQ-20261121-022910）。 */
    public static final String TENANT_PACKAGE_IN_USE = "platform-system-14-0003";

    /** 套餐菜单集非法（menuIds 含不存在或已删菜单，409；REQ-20261121-022910）。 */
    public static final String TENANT_PACKAGE_MENU_INVALID = "platform-system-14-0004";

    /** 绑定套餐租户分配套餐范围外菜单被拒绝（403；REQ-20261121-022910）。 */
    public static final String TENANT_PACKAGE_MENU_FORBIDDEN = "platform-system-14-0005";

    /** 套餐能力集非法（capabilities 含未知能力码，409；REQ-20261121-054613，对齐 14-0004 引用集先例）。 */
    public static final String TENANT_CAPABILITY_INVALID = "platform-system-14-0006";

    private SystemErrorCodes() {
    }
}
