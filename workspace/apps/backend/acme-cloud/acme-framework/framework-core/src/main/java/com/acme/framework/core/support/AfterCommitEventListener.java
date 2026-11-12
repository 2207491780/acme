package com.acme.framework.core.support;

/**
 * 事务提交后事件监听接口（FR-003）：自定义泛型订阅，按 ResolvableType 匹配（DQ-001）；
 * 投递范围仅进程内监听方（Q-006）。
 */
@FunctionalInterface
public interface AfterCommitEventListener<T> {

    /**
     * 接收事件（BR-013 提交后投递；BR-019 监听异常不向发布方传播）。
     */
    void onEvent(T event);
}
