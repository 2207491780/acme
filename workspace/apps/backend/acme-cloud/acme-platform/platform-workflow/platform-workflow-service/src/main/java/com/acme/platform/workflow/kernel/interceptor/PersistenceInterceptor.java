package com.acme.platform.workflow.kernel.interceptor;

import com.acme.platform.workflow.kernel.CommandInterceptor;
import com.acme.platform.workflow.kernel.CommandInvocation;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Persistence 拦截器（设计规格 §3.1：同事务落库）：按登记序执行 CommandContext 持久化队列
 * （乐观锁冲突经 FlexRepositorySupport 转 STALE_WRITE 上抛——重试环/回滚判定面）。
 */
@Component
@Order(60)
public class PersistenceInterceptor implements CommandInterceptor {

    @Override
    public Object intercept(CommandInvocation invocation) {
        Runnable operation;
        while ((operation = invocation.context().drainPersistenceOps().poll()) != null) {
            operation.run();
        }
        return invocation.proceed();
    }
}
