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
    PERMISSION_DENIED(SystemErrorCodes.PERMISSION_DENIED, "无操作权限", 403);

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
