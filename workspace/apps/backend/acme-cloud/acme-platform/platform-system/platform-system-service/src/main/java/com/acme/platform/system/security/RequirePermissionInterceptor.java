package com.acme.platform.system.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口级权限拦截器（架构设计 5.2）：解析 HandlerMethod 上的 {@link RequirePermission}，
 * 交由 {@link PermissionDecisionService} 本地判定；异常经 framework-web 全局异常映射为标准 403 信封。
 */
@Component
public class RequirePermissionInterceptor implements HandlerInterceptor {

    private final PermissionDecisionService decisionService;

    public RequirePermissionInterceptor(PermissionDecisionService decisionService) {
        this.decisionService = decisionService;
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
        decisionService.checkCurrentSubject(required.value());
        return true;
    }
}
