package com.acme.platform.workflow.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限标注（workflow 域 Admin 端点）：权限目录数据归 platform-system 独占（M-03），
 * 判定经 AuthorizationClient 远程执行（platform-infra/scheduler 同先例）。
 * 编码取值对齐 platform-system V27 种子（REQ-20261121-142316 确认⑦），
 * 常量见 platform-system-api SystemPermissions（WORKFLOW_* 段）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 权限点编码（platform-system 权限目录，SystemPermissions 常量）。 */
    String value();
}
