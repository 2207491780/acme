package com.acme.platform.system;

import com.acme.common.core.ErrorCode;
import com.acme.framework.core.support.HttpStatusAware;
import com.acme.platform.system.api.SystemErrorCodes;

/**
 * 系统管理域错误码枚举（10.5.2 三段式，serviceId=platform-system；架构设计 8.1 分段表）。
 * 码值与 platform-system-api SystemErrorCodes 常量一一对应（跨服务判定取常量、本服务抛枚举）。
 */
public enum SystemError implements ErrorCode, HttpStatusAware {

    /** 用户不存在。 */
    USER_NOT_FOUND(SystemErrorCodes.USER_NOT_FOUND, "用户不存在", 404),

    /** 用户名已存在。 */
    USERNAME_DUPLICATED(SystemErrorCodes.USERNAME_DUPLICATED, "用户名已存在", 409),

    /** 用户删除被拒绝（内置超管 / 操作者本人）。 */
    USER_DELETE_DENIED(SystemErrorCodes.USER_DELETE_DENIED, "该用户不允许删除", 409),

    /** 角色不存在。 */
    ROLE_NOT_FOUND(SystemErrorCodes.ROLE_NOT_FOUND, "角色不存在", 404),

    /** 租户内角色编码已存在。 */
    ROLE_CODE_DUPLICATED(SystemErrorCodes.ROLE_CODE_DUPLICATED, "角色编码已存在", 409),

    /** 角色删除被拒绝（内置角色 / 存在子角色 / 存在用户绑定）。 */
    ROLE_DELETE_DENIED(SystemErrorCodes.ROLE_DELETE_DENIED, "该角色不允许删除", 409),

    /** 角色父节点调整成环（目标父为自身或其子孙）。 */
    ROLE_PARENT_CYCLE(SystemErrorCodes.ROLE_PARENT_CYCLE, "不能将角色移动到自身或其子角色之下", 409),

    /** 内置角色不允许调整父角色。 */
    ROLE_REPARENT_DENIED(SystemErrorCodes.ROLE_REPARENT_DENIED, "内置角色不允许调整上级角色", 409),

    /** 角色编码为平台保留编码（super_admin / tenant_admin，任何租户不得自建）。 */
    ROLE_CODE_RESERVED(SystemErrorCodes.ROLE_CODE_RESERVED, "该角色编码为平台保留编码，不允许使用", 409),

    /** 非内置角色绑定平台保留域权限点被拒绝。 */
    ROLE_GRANT_RESERVED(SystemErrorCodes.ROLE_GRANT_RESERVED, "平台保留域权限点不允许授予非内置角色", 409),

    /** 数据范围设置非法（档位未知 / CUSTOM 未传部门集或部门集含未知部门，REQ-20261114-120104）。 */
    DATA_SCOPE_INVALID(SystemErrorCodes.DATA_SCOPE_INVALID, "数据范围设置非法", 409),

    /** 权限点不存在。 */
    PERMISSION_NOT_FOUND(SystemErrorCodes.PERMISSION_NOT_FOUND, "权限点不存在", 404),

    /** 权限点编码已存在。 */
    PERMISSION_CODE_DUPLICATED(SystemErrorCodes.PERMISSION_CODE_DUPLICATED, "权限点编码已存在", 409),

    /** 权限点删除被拒绝（存在角色绑定）。 */
    PERMISSION_DELETE_DENIED(SystemErrorCodes.PERMISSION_DELETE_DENIED, "该权限点已被角色绑定，不允许删除", 409),

    /** 菜单不存在。 */
    MENU_NOT_FOUND(SystemErrorCodes.MENU_NOT_FOUND, "菜单不存在", 404),

    /** 菜单删除被拒绝（存在子菜单 / 角色绑定）。 */
    MENU_DELETE_DENIED(SystemErrorCodes.MENU_DELETE_DENIED, "该菜单不允许删除", 409),

    /** 字典类型不存在。 */
    DICT_TYPE_NOT_FOUND(SystemErrorCodes.DICT_TYPE_NOT_FOUND, "字典类型不存在", 404),

    /** 租户内字典编码已存在。 */
    DICT_CODE_DUPLICATED(SystemErrorCodes.DICT_CODE_DUPLICATED, "字典编码已存在", 409),

    /** 同类型下字典项值已存在。 */
    DICT_ITEM_DUPLICATED(SystemErrorCodes.DICT_ITEM_DUPLICATED, "同类型下字典项值已存在", 409),

    /** 字典类型删除被拒绝（存在字典项）。 */
    DICT_TYPE_DELETE_DENIED(SystemErrorCodes.DICT_TYPE_DELETE_DENIED, "该字典类型下存在字典项，不允许删除", 409),

    /** 租户不存在。 */
    TENANT_NOT_FOUND(SystemErrorCodes.TENANT_NOT_FOUND, "租户不存在", 404),

    /** 租户编码已存在。 */
    TENANT_CODE_DUPLICATED(SystemErrorCodes.TENANT_CODE_DUPLICATED, "租户编码已存在", 409),

    /** 默认租户停用被拒绝。 */
    TENANT_DISABLE_DENIED(SystemErrorCodes.TENANT_DISABLE_DENIED, "默认租户不允许停用", 409),

    /** 接口级权限点不足。 */
    PERMISSION_DENIED(SystemErrorCodes.PERMISSION_DENIED, "无操作权限", 403),

    /** 公告已发布，不允许修改或删除（REQ-20261114-120102）。 */
    NOTICE_PUBLISHED(SystemErrorCodes.NOTICE_PUBLISHED, "公告已发布，不允许修改或删除", 409),

    /** 公告不存在（REQ-20261114-120102 实施增补：publish/update 对不存在 id 不可静默成功）。 */
    NOTICE_NOT_FOUND(SystemErrorCodes.NOTICE_NOT_FOUND, "公告不存在", 404),

    /** 站内信不存在（REQ-20261114-120102）。 */
    MESSAGE_NOT_FOUND(SystemErrorCodes.MESSAGE_NOT_FOUND, "站内信不存在", 404),

    /** 无权操作他人站内信（REQ-20261114-120102）。 */
    MESSAGE_FORBIDDEN(SystemErrorCodes.MESSAGE_FORBIDDEN, "无权操作他人站内信", 403),

    /** 部门不存在（REQ-20261114-120104）。 */
    DEPT_NOT_FOUND(SystemErrorCodes.DEPT_NOT_FOUND, "部门不存在", 404),

    /** 部门删除被拒绝（存在子部门，REQ-20261114-120104 确认记录③）。 */
    DEPT_HAS_CHILDREN(SystemErrorCodes.DEPT_HAS_CHILDREN, "该部门下存在子部门，不允许删除", 409),

    /** 部门删除被拒绝（存在用户归属，REQ-20261114-120104 确认记录③）。 */
    DEPT_HAS_USERS(SystemErrorCodes.DEPT_HAS_USERS, "该部门下存在用户，不允许删除", 409),

    /** 部门父节点调整成环（目标父为自身或其子孙，REQ-20261114-120104）。 */
    DEPT_PARENT_CYCLE(SystemErrorCodes.DEPT_PARENT_CYCLE, "不能将部门移动到自身或其子部门之下", 409),

    /** 岗位不存在（REQ-20261114-120104）。 */
    POSITION_NOT_FOUND(SystemErrorCodes.POSITION_NOT_FOUND, "岗位不存在", 404),

    /** 岗位删除被拒绝（存在用户引用，REQ-20261114-120104）。 */
    POSITION_IN_USE(SystemErrorCodes.POSITION_IN_USE, "该岗位已被用户引用，不允许删除", 409),

    /** 租户内岗位编码已存在（REQ-20261114-120104 实施增补）。 */
    POSITION_CODE_DUPLICATED(SystemErrorCodes.POSITION_CODE_DUPLICATED, "岗位编码已存在", 409),

    /** 系统配置闲置锁屏时长取值非法（REQ-20261116-230354，对齐 DATA_SCOPE_INVALID 档位非法先例）。 */
    SYSTEM_CONFIG_INVALID(SystemErrorCodes.SYSTEM_CONFIG_INVALID, "闲置锁屏时长取值非法", 409),

    /** 水印模板含未知占位符（REQ-20261117-180000）。 */
    SYSTEM_WATERMARK_TEMPLATE_INVALID(SystemErrorCodes.SYSTEM_WATERMARK_TEMPLATE_INVALID,
            "水印模板包含不支持的占位符", 400),

    // ---- 13 段：个人中心自助资料域（REQ-20261119-082531 确认记录①）----

    /** 个人资料非法（avatar_url 非 http(s)/相对地址等自助资料值域违规，MeUseCase 校验承载）。 */
    PROFILE_INVALID(SystemErrorCodes.PROFILE_INVALID, "个人资料非法", 400),

    // ---- 14 段：租户套餐域（REQ-20261121-022910）----

    /** 套餐名称已存在（创建 / 更新撞既有名）。 */
    TENANT_PACKAGE_DUPLICATE(SystemErrorCodes.TENANT_PACKAGE_DUPLICATE, "套餐名称已存在", 409),

    /** 套餐不存在（详情 / 更新 / 删除对不存在 id 不可静默成功；租户绑定非法套餐同码）。 */
    TENANT_PACKAGE_NOT_FOUND(SystemErrorCodes.TENANT_PACKAGE_NOT_FOUND, "套餐不存在", 404),

    /** 套餐被租户绑定，删除被拒绝（确认④：占用不删除）。 */
    TENANT_PACKAGE_IN_USE(SystemErrorCodes.TENANT_PACKAGE_IN_USE, "该套餐已被租户绑定，不允许删除", 409),

    /** 套餐菜单集非法（menuIds 含不存在或已删菜单，确认④；对齐 DATA_SCOPE_INVALID 引用集先例）。 */
    TENANT_PACKAGE_MENU_INVALID(SystemErrorCodes.TENANT_PACKAGE_MENU_INVALID, "套餐菜单集包含不存在的菜单", 409),

    /** 绑定套餐租户分配套餐范围外菜单被拒绝（确认⑤，写入整体拒绝，403 对齐 MESSAGE_FORBIDDEN 先例）。 */
    TENANT_PACKAGE_MENU_FORBIDDEN(SystemErrorCodes.TENANT_PACKAGE_MENU_FORBIDDEN, "不能分配套餐范围外的菜单", 403);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    SystemError(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
