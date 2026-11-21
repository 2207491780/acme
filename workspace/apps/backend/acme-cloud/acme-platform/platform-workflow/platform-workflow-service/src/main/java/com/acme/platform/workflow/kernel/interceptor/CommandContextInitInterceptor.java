package com.acme.platform.workflow.kernel.interceptor;

import com.acme.platform.workflow.kernel.CommandInterceptor;
import com.acme.platform.workflow.kernel.CommandInvocation;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * ContextInit 拦截器（链首，设计规格 §3.1）：上下文构建由 WorkflowCommandExecutor 委托本位
 * 承载（Clock 统一时刻 FR-019），链内直通（首拦截器收到 executor 预建上下文）。
 */
@Component
@Order(10)
public class CommandContextInitInterceptor implements CommandInterceptor {

    @Override
    public Object intercept(CommandInvocation invocation) {
        return invocation.proceed();
    }
}
