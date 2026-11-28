package com.acme.platform.workflow.kernel.interceptor;

import com.acme.platform.workflow.kernel.CommandInterceptor;
import com.acme.platform.workflow.kernel.CommandInvocation;
import com.acme.platform.workflow.kernel.WorkflowEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * EventPublish 拦截器（设计规格 §3.1 链尾，确认③ AFTER_COMMIT 异步终稿）：
 * 事务提交后发布 CommandContext 收集的领域事件（SPI {@link WorkflowEventPublisher}，
 * 一期 logging no-op、Kafka 归 BPM-5）；事务回滚不发布（AC-12 一致性面——事件注册随每轮
 * 尝试事务，STALE 回滚轮次的事件随之丢弃，重跑轮次重录重注册）。
 *
 * <p>REQ-20261128-183425 P2-12：删除 {@code CompletableFuture.runAsync} 公共池包装，改为
 * afterCommit 线程**直派** {@code publish(event)}——publish 为廉价提交（Kafka 实现内部专属
 * 单线程 executor 异步发送、Logging 实现同步 no-op），提交序=发送入队序（公共池并发提交的
 * 入队次序不定曾破坏跨命令事件全局顺序）；异步性/失败消化语义由发布器实现承载（SPI 口径：
 * 发布失败不反噬命令结果）。</p>
 */
@Component
@Order(70)
public class EventPublishInterceptor implements CommandInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EventPublishInterceptor.class);

    private final WorkflowEventPublisher eventPublisher;

    public EventPublishInterceptor(WorkflowEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Object intercept(CommandInvocation invocation) {
        var context = invocation.context();
        if (!context.events().isEmpty()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            var events = context.events();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // AFTER_COMMIT 终稿：直派发布器（异步性在实现内部）；发布失败不反噬命令结果
                    for (var event : events) {
                        try {
                            eventPublisher.publish(event);
                        } catch (RuntimeException ex) {
                            log.warn("领域事件发布失败（不反噬命令）：type={} instance={}",
                                    event.eventType(), event.instanceId(), ex);
                        }
                    }
                }
            });
        }
        return invocation.proceed();
    }
}
