package com.acme.framework.cache.interceptor;

/**
 * 缓存失效事件（FR-006）：事务激活时经 framework-core TransactionEventPublisher 延迟至提交后执行，
 * 无事务时立即投递（DefaultTransactionEventPublisher 缺省语义）。
 */
public record CacheEvictEvent(String key) {

    public CacheEvictEvent {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空");
        }
    }
}
