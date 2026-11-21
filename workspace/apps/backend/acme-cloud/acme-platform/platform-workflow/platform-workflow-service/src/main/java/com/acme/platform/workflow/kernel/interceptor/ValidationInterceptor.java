package com.acme.platform.workflow.kernel.interceptor;

import com.acme.platform.workflow.kernel.CommandInterceptor;
import com.acme.platform.workflow.kernel.CommandInvocation;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Validation 拦截器（设计规格 §3.1）：状态机前置校验（WorkflowCommand.validate；失败抛域错误
 * 驱动整体回滚，AC-12）。
 */
@Component
@Order(40)
public class ValidationInterceptor implements CommandInterceptor {

    @Override
    public Object intercept(CommandInvocation invocation) {
        invocation.context().command().validate(invocation.context());
        return invocation.proceed();
    }
}
