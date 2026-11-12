package com.acme.business.content.security;

import com.acme.business.content.infrastructure.client.AuthorizationGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口级权限拦截器（内容域）：解析 HandlerMethod 上的 {@link RequirePermission}，
 * 交由 AuthorizationGateway 远程判定（判定失败 fail-closed 403）；
 * 异常经 framework-web 全局异常映射为标准 403 信封（platform-infra 同型先例）。
 * 网关经 ObjectProvider 惰性解析：Feign 客户端创建会牵动 WebMvc 配置装配，
 * 构造期直接依赖会形成「Feign→WebMvc 配置→拦截器→网关→Feign」环（启动环依赖）。
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
