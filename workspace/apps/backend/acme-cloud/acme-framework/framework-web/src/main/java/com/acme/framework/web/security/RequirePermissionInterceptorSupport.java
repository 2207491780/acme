package com.acme.framework.web.security;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 接口级权限拦截器骨架（REQ-20261127-161433 P2-19：infra/scheduler/workflow 三域逐字一致
 * 拦截器 + system 本地判定特化的公共骨架上提）——解析 HandlerMethod 上的
 * {@link RequirePermission}（方法级优先、类级兜底=类型级全端点生效，P3-08 统一口径），
 * 交由构造注入的 {@link PermissionDecider} 按域语义要求判定。
 *
 * <p>域内子类只保留差异面：远程域注入 {@code ObjectProvider<AuthorizationGateway>}（惰性解析，
 * Feign→WebMvc 配置→拦截器→网关→Feign 启动环防断，REQ-20261118-002130 口径）、system 注入
 * 本地 {@code PermissionDecisionService}；显式 bean 名（如 infraRequirePermissionInterceptor）
 * 在单体裁装下消歧，惯例保留。</p>
 */
public abstract class RequirePermissionInterceptorSupport implements HandlerInterceptor {

    private final PermissionDecider decider;

    protected RequirePermissionInterceptorSupport(PermissionDecider decider) {
        this.decider = decider;
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
        decider.requirePermission(required.value());
        return true;
    }
}
