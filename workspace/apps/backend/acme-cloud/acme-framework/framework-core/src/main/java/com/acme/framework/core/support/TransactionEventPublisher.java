package com.acme.framework.core.support;

/**
 * 事务提交后事件发布接口（FR-003）。
 */
@FunctionalInterface
public interface TransactionEventPublisher {

    /**
     * 发布事件（BR-020 任意对象；BR-013/BR-017 投递时机；BR-018 同事务 FIFO）。
     */
    void publish(Object event);
}
