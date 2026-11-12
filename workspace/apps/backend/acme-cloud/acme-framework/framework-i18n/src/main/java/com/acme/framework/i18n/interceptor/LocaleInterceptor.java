package com.acme.framework.i18n.interceptor;

import com.acme.common.context.LocaleContext;
import com.acme.framework.i18n.support.I18nLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;

/**
 * Locale 解析拦截（FR-006、架构设计 7.1/7.2）：头解析唯一入口 {@link I18nLocaleResolver}，
 * 解析结果写 {@link LocaleContext} 供业务只读（11.6.3 同构）；请求结束 clear（Modules.md 2.4.3）。
 */
public class LocaleInterceptor implements HandlerInterceptor {

    private final I18nLocaleResolver resolver;
    private final String headerName;
    private final String overrideHeader;

    public LocaleInterceptor(I18nLocaleResolver resolver, String headerName, String overrideHeader) {
        this.resolver = resolver;
        this.headerName = headerName;
        this.overrideHeader = overrideHeader;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Locale locale = resolver.resolve(request.getHeader(overrideHeader), request.getHeader(headerName));
        LocaleContext.bind(new LocaleContext(locale));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        LocaleContext.clear();
    }
}
