package com.acme.platform.infra.security;

import com.acme.framework.web.security.PermissionDecider;
import com.acme.framework.web.security.RequirePermissionInterceptorSupport;
import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 接口级权限拦截器（infra 域）：解析 {@code @RequirePermission}，交由 {@link AuthorizationGateway}
 * 远程要求判定（权限目录归 platform-system，M-03）；判定骨架上提 framework-web
 * （REQ-20261127-161433 P2-19，域内仅保留决策策略与惰性解析差异面）。
 * 网关经 ObjectProvider 惰性解析：Feign 客户端创建会牵动 WebMvc 配置装配，
 * 构造期直接依赖会形成「Feign→WebMvc 配置→拦截器→网关→Feign」环（启动环依赖，
 * business-api-integration G3 后端联调暴露，与 business-content 同步修复）。
 * 显式 bean 名（REQ-20261118-002130）：与 system 域同名类在单体裁装下消歧，纯命名注解无行为变化。
 */
@Component("infraRequirePermissionInterceptor")
public class RequirePermissionInterceptor extends RequirePermissionInterceptorSupport {

    public RequirePermissionInterceptor(ObjectProvider<AuthorizationGateway> authorizationGateways) {
        super((PermissionDecider) code -> authorizationGateways.getObject().requirePermission(code));
    }
}
