package com.acme.business.content.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限标注（内容域执行面，架构设计 6.5 逐端点）：权限目录数据归 platform-system 独占（M-03），
 * 判定经 AuthorizationClient 远程执行（platform-infra 同型先例）；
 * 批量端点按动作动态取权限点（QD-05），在应用层判定，不适用本标注。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 权限点编码（business-content-api ContentPermissions 常量）。 */
    String value();
}
