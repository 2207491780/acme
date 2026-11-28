package com.acme.framework.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限点标注（REQ-20261127-161433 P2-20/P3-08：四域同名注解上提公共契约，
 * {@code @Target} 统一 {@code {METHOD, TYPE}}——system 域原 METHOD 离群面随统一消解）。
 * 标注于 Controller 方法或类（类级=全端点生效），由各域权限拦截器解析并按域判定语义执行：
 * 远程域（infra/scheduler/workflow）经 AuthorizationGateway 要求判定，system 域经本地
 * PermissionDecisionService 对照判定——判定语义差异留在域内拦截器/策略，注解纯元数据。
 *
 * <p>编码取值见 platform-system-api SystemPermissions（framework 不硬编码角色与权限点，8.2.1）。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 所需权限点编码（如 {@code system:user:create}）。 */
    String value();
}
