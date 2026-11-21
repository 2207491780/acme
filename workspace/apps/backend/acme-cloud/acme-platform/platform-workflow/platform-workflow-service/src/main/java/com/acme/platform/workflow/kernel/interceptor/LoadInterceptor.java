package com.acme.platform.workflow.kernel.interceptor;

import com.acme.platform.workflow.kernel.CommandInterceptor;
import com.acme.platform.workflow.kernel.CommandInvocation;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Load 拦截器（命令链实现细化位，位于重试环内）：聚合装载——乐观锁重试每轮重载
 * （WorkflowCommand.load 三段式契约第一段；重试后上次装载实体作废，杜绝半新半旧状态）。
 */
@Component
@Order(30)
public class LoadInterceptor implements CommandInterceptor {

    @Override
    public Object intercept(CommandInvocation invocation) {
        invocation.context().command().load(invocation.context());
        return invocation.proceed();
    }
}
