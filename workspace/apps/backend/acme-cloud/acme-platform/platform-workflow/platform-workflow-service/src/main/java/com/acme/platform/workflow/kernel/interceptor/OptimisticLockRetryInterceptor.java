package com.acme.platform.workflow.kernel.interceptor;

import com.acme.common.core.BizException;
import com.acme.framework.flex.support.FlexError;
import com.acme.platform.workflow.kernel.CommandInterceptor;
import com.acme.platform.workflow.kernel.CommandInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OptimisticLockRetry 拦截器（设计规格 §3.1：≤3 次重试、指数退避 50/100/200ms——确认③终稿）：
 * 捕获 mybatis-flex 乐观锁冲突（FlexError.STALE_WRITE，AC-7 并发竞争面），每轮尝试以独立事务
 * 重跑 Load→Validation→Execution→Persistence→EventPublish（STALE 本轮整体回滚后重载聚合——
 * 「同事务内重试」会残留本轮部分写并污染后续轮次读面，故事务边界收敛于本拦截器而非执行器外层；
 * 命令失败整体回滚无部分推进的 AC-12 语义由每轮事务回滚承载）。非 STALE 异常原样上抛（本轮回滚）。
 */
@Component
@Order(20)
public class OptimisticLockRetryInterceptor implements CommandInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetryInterceptor.class);

    /** 重试上限（确认③：≤3）。 */
    static final int MAX_RETRIES = 3;

    /** 退避序列毫秒（确认③：50/100/200）。 */
    static final long[] BACKOFF_MS = {50L, 100L, 200L};

    private final TransactionTemplate transactionTemplate;

    public OptimisticLockRetryInterceptor(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Object intercept(CommandInvocation invocation) {
        BizException lastConflict = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                log.info("乐观锁冲突重试：attempt={} command={}", attempt,
                        invocation.context().command().getClass().getSimpleName());
                sleep(BACKOFF_MS[attempt - 1]);
            }
            // 每轮清空事件（重跑 execute 段重录，防重复发布；afterCommit 随本轮事务成败）
            invocation.context().clearEvents();
            try {
                // 单命令单事务：本轮 Load~EventPublish 整体提交/回滚
                return transactionTemplate.execute(status -> invocation.proceed());
            } catch (BizException ex) {
                if (!FlexError.STALE_WRITE.code().equals(ex.errorCode().code())) {
                    throw ex;
                }
                lastConflict = ex;
            }
        }
        throw lastConflict;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("乐观锁重试等待被中断", ex);
        }
    }
}
