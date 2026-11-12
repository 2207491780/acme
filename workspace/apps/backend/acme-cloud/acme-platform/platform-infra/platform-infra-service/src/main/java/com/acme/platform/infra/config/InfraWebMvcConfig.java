package com.acme.platform.infra.config;

import com.acme.platform.infra.security.RequirePermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 装配（10.1 接口层权限标注落点）：注册接口级权限拦截器。
 */
@Configuration
public class InfraWebMvcConfig implements WebMvcConfigurer {

    private final RequirePermissionInterceptor requirePermissionInterceptor;

    public InfraWebMvcConfig(RequirePermissionInterceptor requirePermissionInterceptor) {
        this.requirePermissionInterceptor = requirePermissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requirePermissionInterceptor).addPathPatterns("/**");
    }
}
