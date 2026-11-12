package com.acme.framework.cache.support;

import com.acme.common.core.BizException;
import com.acme.framework.cache.config.CacheProperties;
import com.acme.framework.cache.config.L2FailurePolicy;
import com.acme.framework.cache.internal.CaffeineL1Cache;
import com.acme.framework.cache.internal.L2Operations;
import com.acme.framework.cache.interceptor.CacheEvictEvent;
import com.acme.framework.cache.interceptor.L1InvalidationBus;
import com.acme.framework.cache.interceptor.PenetrationGuard;
import com.acme.framework.core.support.AfterCommitEventListener;
import com.acme.framework.core.support.TransactionEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;

/**
 * 二级缓存门面（FR-005/AC-005）：读=L1→L2→回源回填两层（L1 命中零 L2 访问，NFR-003）；
 * 写/删=evict-on-write：先删 L2 → 广播清各实例 L1（FR-006，不再写穿透）。
 * 失效经 framework-core 事务提交后事件延迟（Spring 事务激活时），无事务立即执行；
 * 广播消息带来源实例 id，收到后跳过自身。回源异常不写入任何层。
 */
public class TwoLevelCache implements AfterCommitEventListener<CacheEvictEvent> {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    private final CaffeineL1Cache l1;
    private final L2Operations l2;
    private final L1InvalidationBus invalidationBus;
    private final PenetrationGuard penetrationGuard;
    private final CacheProperties properties;
    private final ObjectProvider<TransactionEventPublisher> eventPublisher;

    public TwoLevelCache(CaffeineL1Cache l1, L2Operations l2, L1InvalidationBus invalidationBus,
                         PenetrationGuard penetrationGuard, CacheProperties properties,
                         ObjectProvider<TransactionEventPublisher> eventPublisher) {
        this.l1 = l1;
        this.l2 = l2;
        this.invalidationBus = invalidationBus;
        this.penetrationGuard = penetrationGuard;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 读取（FR-005）：两层均未命中回源并回填；回源异常不写脏数据（AC-005）。
     */
    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        Object cached = l1.getIfPresent(key);
        if (cached != null) {
            return type.cast(cached);
        }
        T fromL2 = readL2(key, type);
        if (fromL2 != null) {
            l1.put(key, fromL2);
            return fromL2;
        }
        return penetrationGuard.guard(key, () -> {
            // 获得入口锁后双检 L2（防击穿期间他人可能已回填，架构设计 6.4）
            T again = readL2(key, type);
            if (again != null) {
                l1.put(key, again);
                return again;
            }
            T loaded;
            try {
                loaded = loader.get();
            } catch (RuntimeException ex) {
                throw new BizException(CacheError.LOAD_FAILED, "缓存回源失败", ex);
            }
            if (loaded != null) {
                writeL2(key, loaded);
                l1.put(key, loaded);
            }
            return loaded;
        });
    }

    /**
     * 失效（FR-006/AC-006，evict-on-write 口径）：删 L2 → 清本地 L1 → 广播清各实例 L1。
     * Spring 事务激活时经 {@link TransactionEventPublisher} 延迟至提交后执行；无事务立即执行。
     */
    public void evict(String key) {
        TransactionEventPublisher publisher = eventPublisher.getIfAvailable();
        if (publisher != null) {
            publisher.publish(new CacheEvictEvent(key));
            return;
        }
        applyEvict(key);
    }

    /** 事务提交后投递面（FR-006/FR-003）：仅缓存失效事件。 */
    @Override
    public void onEvent(CacheEvictEvent event) {
        applyEvict(event.key());
    }

    /** 实际失效执行：删 L2 → 清本地 L1 → 广播（来源=自身，订阅侧跳过）。 */
    void applyEvict(String key) {
        deleteL2(key);
        l1.invalidate(key);
        invalidationBus.publishEvict(key);
    }

    /** 本实例 L1 清除（订阅回调/兜底路径）。 */
    public void evictLocal(String key) {
        l1.invalidate(key);
    }

    private <T> T readL2(String key, Class<T> type) {
        try {
            return l2.get(key, type);
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return handleL2Failure(ex, null);
        }
    }

    private void writeL2(String key, Object value) {
        try {
            l2.put(key, value, properties.getL2().getTtlSeconds());
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            handleL2Failure(ex, null);
        }
    }

    private void deleteL2(String key) {
        try {
            l2.delete(key);
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            handleL2Failure(ex, null);
        }
    }

    private <T> T handleL2Failure(RuntimeException ex, T fallback) {
        if (properties.getL2Failure() == L2FailurePolicy.FAIL) {
            throw new BizException(CacheError.CACHE_UNAVAILABLE, "L2 缓存不可用", ex);
        }
        // DEGRADE：降级直通（可用性优先，取舍 #13），回源/本地路径继续
        log.warn("L2 缓存不可用，降级直通回源（FR-005）: {}", ex.getMessage());
        return fallback;
    }
}
