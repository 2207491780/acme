package com.acme.platform.system.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限点标注（架构设计 5.2，FR-008）：标注于 Controller 方法（接口层权限标注，10.1），
 * 拦截器从 UserContext.roles()（令牌 claims）本地查询 sys_role_permission 对照判定（无远程调用，M-05 安全）；
 * 未命中抛 PERMISSION_DENIED（403）。超管能力来自种子对内置 super_admin 的全量权限点绑定，
 * 无编码短路（角色编码不是信任锚，review 修复 P0）。
 * 编码取值见 platform-system-api SystemPermissions（framework 不硬编码角色与权限点，8.2.1）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 所需权限点编码（如 {@code system:user:create}）。 */
    String value();
}
