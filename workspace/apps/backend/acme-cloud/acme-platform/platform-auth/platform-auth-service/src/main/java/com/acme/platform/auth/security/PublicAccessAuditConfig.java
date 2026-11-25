package com.acme.platform.auth.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 公开端点访问审计装配（REQ-20261125-001736 AC-1）：{@link PublicAccessAuditInterceptor} 仅注册在
 * 三个登录页公开端点路径上（tenants / captcha-policy / public-config）；GET 限定由拦截器内部判定
 * （URL pattern 无法表达方法约束）。响应与行为零变化——拦截器恒放行、不写响应。
 */
@Configuration(proxyBeanMethods = false)
public class PublicAccessAuditConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PublicAccessAuditInterceptor())
                .addPathPatterns(
                        "/admin/v1/auth/tenants",
                        "/admin/v1/auth/captcha-policy",
                        "/admin/v1/auth/public-config");
    }
}
