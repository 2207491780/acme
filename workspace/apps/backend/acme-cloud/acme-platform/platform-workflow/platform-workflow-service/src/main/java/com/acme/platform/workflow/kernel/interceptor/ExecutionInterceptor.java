package com.acme.platform.workflow.kernel.interceptor;

import com.acme.platform.workflow.kernel.CommandInterceptor;
import com.acme.platform.workflow.kernel.CommandInvocation;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Execution 拦截器（设计规格 §3.1）：节点行为推进与实体变更（WorkflowCommand.execute；
 * 直写语义——本事务内即时落库）。返回值回填仅在命令未自行延迟设置结果时生效
 * （新建实体 id 落库分配——延迟面命令经 context.setResult 在 Persistence 段前回填）。
 */
@Component
@Order(50)
public class ExecutionInterceptor implements CommandInterceptor {

    @Override
    public Object intercept(CommandInvocation invocation) {
        Object result = invocation.context().command().execute(invocation.context());
        if (result != null) {
            invocation.context().setResult(result);
        }
        return invocation.proceed();
    }
}
