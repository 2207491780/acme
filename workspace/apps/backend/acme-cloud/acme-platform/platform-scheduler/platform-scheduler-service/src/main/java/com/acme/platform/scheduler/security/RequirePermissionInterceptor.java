package com.acme.platform.scheduler.security;

import com.acme.framework.web.security.PermissionDecider;
import com.acme.framework.web.security.RequirePermissionInterceptorSupport;
import com.acme.platform.scheduler.infrastructure.client.AuthorizationGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 接口级权限拦截器（scheduler 域）：解析 {@code @RequirePermission}，交由 {@link AuthorizationGateway}
 * 远程要求判定；判定骨架上提 framework-web（REQ-20261127-161433 P2-19，platform-infra 同款）。
 * 网关经 ObjectProvider 惰性解析（构造期直接依赖会形成「Feign→WebMvc 配置→拦截器→网关→Feign」环，
 * platform-infra 同款修复口径）。
 * 显式 bean 名（REQ-20261118-002130 消歧惯例）：与 system/infra 域同名类在单体裁装下消歧。
 */
@Component("schedulerRequirePermissionInterceptor")
public class RequirePermissionInterceptor extends RequirePermissionInterceptorSupport {

    public RequirePermissionInterceptor(ObjectProvider<AuthorizationGateway> authorizationGateways) {
        super((PermissionDecider) code -> authorizationGateways.getObject().requirePermission(code));
    }
}
