package com.acme.platform.infra.security;

import com.acme.platform.infra.infrastructure.client.AuthorizationGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口级权限拦截器（infra 域）：解析 HandlerMethod 上的 {@link RequirePermission}，
 * 交由 {@link AuthorizationGateway} 远程判定（权限目录归 platform-system，M-03）；
 * 异常经 framework-web 全局异常映射为标准 403 信封。
 * 网关经 ObjectProvider 惰性解析：Feign 客户端创建会牵动 WebMvc 配置装配，
 * 构造期直接依赖会形成「Feign→WebMvc 配置→拦截器→网关→Feign」环（启动环依赖，
 * business-api-integration G3 后端联调暴露，与 business-content 同步修复）。
 */
@Component
public class RequirePermissionInterceptor implements HandlerInterceptor {

    private final ObjectProvider<AuthorizationGateway> authorizationGateways;

    public RequirePermissionInterceptor(ObjectProvider<AuthorizationGateway> authorizationGateways) {
        this.authorizationGateways = authorizationGateways;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePermission required = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequirePermission.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequirePermission.class);
        }
        if (required == null) {
            return true;
        }
        authorizationGateways.getObject().requirePermission(required.value());
        return true;
    }
}
