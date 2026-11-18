package com.acme.framework.core.interceptor;

import com.acme.framework.core.internal.AfterCommitEventDispatcher;
import org.springframework.transaction.support.TransactionSynchronization;

import java.util.ArrayList;
import java.util.List;

/**
 * 事务完成拦截点（FR-003）：事务内缓冲（BR-018 FIFO）、提交后投递（BR-013）、回滚丢弃（AC-006）。
 *
 * <p>投递阶段置 delivering/completed 标记，期间新 publish 一律走立即投递（架构设计 4.3 防丢失）。</p>
 */
public class TransactionEventSynchronization implements TransactionSynchronization {

    private enum State {
        OPEN, DELIVERING, COMPLETED
    }

    private final AfterCommitEventDispatcher dispatcher;

    private final List<Object> buffer = new ArrayList<>(4);

    private State state = State.OPEN;

    public TransactionEventSynchronization(AfterCommitEventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 缓冲事件（BR-018）；非开放状态返回 false，由调用方改走立即投递（BR-017 防丢失）。
     */
    public synchronized boolean tryBuffer(Object event) {
        if (state != State.OPEN) {
            return false;
        }
        buffer.add(event);
        return true;
    }

    @Override
    public void afterCommit() {
        List<Object> events;
        synchronized (this) {
            if (state != State.OPEN) {
                return;
            }
            state = State.DELIVERING;
            events = List.copyOf(buffer);
            buffer.clear();
        }
        for (Object event : events) {
            dispatcher.dispatch(event);
        }
        synchronized (this) {
            state = State.COMPLETED;
        }
    }

    @Override
    public void afterCompletion(int status) {
        synchronized (this) {
            if (status != STATUS_COMMITTED) {
                buffer.clear();
            }
            state = State.COMPLETED;
        }
    }
}
