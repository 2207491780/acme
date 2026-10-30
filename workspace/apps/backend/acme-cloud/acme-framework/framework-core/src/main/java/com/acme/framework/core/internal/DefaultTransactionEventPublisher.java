package com.acme.framework.core.internal;

import com.acme.framework.core.interceptor.TransactionEventSynchronization;
import com.acme.framework.core.support.TransactionEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * {@link TransactionEventPublisher} 实现（FR-003）：事务状态分支与同步登记（架构设计 4.3）。
 */
public class DefaultTransactionEventPublisher implements TransactionEventPublisher {

    private final AfterCommitEventDispatcher dispatcher;

    public DefaultTransactionEventPublisher(AfterCommitEventDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
    }

    @Override
    public void publish(Object event) {
        Objects.requireNonNull(event, "event 不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionEventSynchronization synchronization = findOrCreateSynchronization();
            if (synchronization.tryBuffer(event)) {
                return;
            }
        }
        // BR-017 / AC-014：无进行中事务（或投递阶段）立即投递，发布调用返回前完成
        dispatcher.dispatch(event);
    }

    private TransactionEventSynchronization findOrCreateSynchronization() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof TransactionEventSynchronization existing) {
                return existing;
            }
        }
        TransactionEventSynchronization created = new TransactionEventSynchronization(dispatcher);
        TransactionSynchronizationManager.registerSynchronization(created);
        return created;
    }
}
