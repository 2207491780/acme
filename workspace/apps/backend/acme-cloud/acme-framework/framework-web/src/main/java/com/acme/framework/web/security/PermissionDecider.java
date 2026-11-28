package com.acme.framework.web.security;

/**
 * 权限判定策略（REQ-20261127-161433 P2-19：四域拦截器收敛的判定缝）——
 * 契约=「要求判定：未授权即抛」（远程域 AuthorizationGateway.requirePermission 与
 * system 本地 PermissionDecisionService.checkCurrentSubject 同构 throw 语义）；
 * 拒绝/异常映射为标准 403 信封由 framework-web 全局异常翻译承载。
 */
@FunctionalInterface
public interface PermissionDecider {

    /**
     * 要求当前主体具备权限点；未授权/主体缺失抛出（全局异常翻译映射 403）。
     *
     * @param permissionCode 权限点编码（{@code @RequirePermission.value()}）
     */
    void requirePermission(String permissionCode);
}
