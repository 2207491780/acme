package com.acme.platform.infra.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限标注（infra 域执行面）：权限目录数据归 platform-system 独占（M-03），
 * 判定经 AuthorizationClient 远程执行（与 platform-auth 跨域权限判定同先例）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 权限点编码（platform-system 权限目录，SystemPermissions 常量）。 */
    String value();
}
