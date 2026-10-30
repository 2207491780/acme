package com.acme.framework.core.internal;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * 应用上下文持有者（FR-004）：由自动装配注册；未注册即未就绪（架构设计 4.4）。
 */
public class ApplicationContextRegistry implements ApplicationContextAware, DisposableBean {

    private static volatile ApplicationContext context;

    private ApplicationContext self;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.self = applicationContext;
        context = applicationContext;
    }

    /** 当前上下文；未注册返回 null（FR-004 未就绪判定）。 */
    public static ApplicationContext get() {
        return context;
    }

    /** 解绑（测试与关闭路径）。 */
    public static void clear() {
        context = null;
    }

    @Override
    public void destroy() {
        if (self != null && context == self) {
            context = null;
        }
    }
}
