package com.acme.framework.cache.support;

import com.acme.common.core.BizException;
import com.acme.framework.cache.config.CacheProperties;
import com.acme.framework.cache.config.L2FailurePolicy;
import com.acme.framework.cache.internal.CaffeineL1Cache;
import com.acme.framework.cache.internal.L2Operations;
import com.acme.framework.cache.interceptor.CacheEvictEvent;
import com.acme.framework.cache.interceptor.L1InvalidationBus;
import com.acme.framework.cache.interceptor.PenetrationGuard;
import com.acme.framework.core.support.TransactionEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 二级缓存读写路径（FR-005/006、AC-005/006/007、NFR-003、M-12）。
 */
class TwoLevelCacheTest {

    /** 记录行为的内存 L2 假件。 */
    static final class FakeL2 implements L2Operations {

        final Map<String, Object> store = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final List<Long> writeTtls = new ArrayList<>();
        RuntimeException failure;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key, Class<T> type) {
            reads.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return (T) store.get(key);
        }

        @Override
        public void put(String key, Object value, long ttlSeconds) {
            writes.incrementAndGet();
            writeTtls.add(ttlSeconds);
            if (failure != null) {
                throw failure;
            }
            store.put(key, value);
        }

        @Override
        public void delete(String key) {
            if (failure != null) {
                throw failure;
            }
            store.remove(key);
        }
    }

    /** 记录广播的失效总线假件。 */
    static final class FakeBus extends L1InvalidationBus {

        final java.util.List<String> published = new java.util.ArrayList<>();

        FakeBus() {
            super(null, "test-channel", new CaffeineL1Cache(10, 60), "self-1");
        }

        @Override
        public void publishEvict(String key) {
            published.add(key);
        }
    }

    /** 可延迟投递的事件发布假件（事务感知面）。 */
    static final class DeferredPublisher implements TransactionEventPublisher {

        final List<Object> events = new ArrayList<>();

        @Override
        public void publish(Object event) {
            events.add(event);
        }
    }

    private final FakeL2 l2 = new FakeL2();
    private final CaffeineL1Cache l1 = new CaffeineL1Cache(100, 60);
    private final FakeBus bus = new FakeBus();
    private final CacheProperties properties = new CacheProperties();
    private final AtomicInteger loads = new AtomicInteger();

    private TwoLevelCache cache() {
        PenetrationGuard guard = new PenetrationGuard(false, 0, 0, null);
        return new TwoLevelCache(l1, l2, bus, guard, properties, emptyPublisher());
    }

    private static ObjectProvider<TransactionEventPublisher> emptyPublisher() {
        return new ObjectProvider<>() {
            @Override
            public TransactionEventPublisher getObject(Object... args) {
                return null;
            }

            @Override
            public TransactionEventPublisher getObject() {
                return null;
            }

            @Override
            public TransactionEventPublisher getIfAvailable() {
                return null;
            }

            @Override
            public TransactionEventPublisher getIfUnique() {
                return null;
            }
        };
    }

    private String load() {
        loads.incrementAndGet();
        return "value-" + loads.get();
    }

    // ---------- AC-005 ----------

    @Test
    void l1HitDoesNotTouchL2() {
        TwoLevelCache cache = cache();
        l1.put("k", "l1-value");
        assertThat(cache.get("k", String.class, this::load)).isEqualTo("l1-value");
        assertThat(l2.reads).hasValue(0);
    }

    @Test
    void l2HitFillsL1AndNextHitStopsAtL1() {
        TwoLevelCache cache = cache();
        l2.store.put("k", "l2-value");
        assertThat(cache.get("k", String.class, this::load)).isEqualTo("l2-value");
        assertThat(l2.reads).hasValue(1);
        assertThat(cache.get("k", String.class, this::load)).isEqualTo("l2-value");
        assertThat(l2.reads).hasValue(1);
        assertThat(loads).hasValue(0);
    }

    @Test
    void missLoadsAndFillsBothLayersWithDefaultTtl() {
        TwoLevelCache cache = cache();
        assertThat(cache.get("k", String.class, this::load)).isEqualTo("value-1");
        assertThat(l2.store).containsEntry("k", "value-1");
        assertThat(l2.writeTtls).containsExactly(1800L);
        assertThat(loads).hasValue(1);
        assertThat(cache.get("k", String.class, this::load)).isEqualTo("value-1");
        assertThat(loads).hasValue(1);
    }

    @Test
    void loaderFailureWritesNothing() {
        TwoLevelCache cache = cache();
        assertThatThrownBy(() -> cache.get("k", String.class, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(BizException.class);
        assertThat(l2.store).doesNotContainKey("k");
        assertThat(l1.getIfPresent("k")).isNull();
    }

    // ---------- FR-006 evict-on-write ----------

    @Test
    void evictDeletesL2ThenBroadcasts() {
        TwoLevelCache cache = cache();
        l2.store.put("k", "v");
        l1.put("k", "v");
        cache.evict("k");
        assertThat(l2.store).doesNotContainKey("k");
        assertThat(l1.getIfPresent("k")).isNull();
        assertThat(bus.published).containsExactly("k");
    }

    @Test
    void writeInvalidatesBothLayersInsteadOfWriteThrough() {
        TwoLevelCache cache = cache();
        l2.store.put("k", "stale");
        l1.put("k", "stale");
        cache.evict("k");
        assertThat(l2.store).doesNotContainKey("k");
        assertThat(l1.getIfPresent("k")).isNull();
        assertThat(bus.published).containsExactly("k");
    }

    @Test
    void transactionAwareEvictDefersUntilCommitSignal() {
        DeferredPublisher publisher = new DeferredPublisher();
        PenetrationGuard guard = new PenetrationGuard(false, 0, 0, null);
        TwoLevelCache cache = new TwoLevelCache(l1, l2, bus, guard, properties,
                new ObjectProvider<>() {
                    @Override
                    public TransactionEventPublisher getObject(Object... args) {
                        return publisher;
                    }

                    @Override
                    public TransactionEventPublisher getObject() {
                        return publisher;
                    }

                    @Override
                    public TransactionEventPublisher getIfAvailable() {
                        return publisher;
                    }

                    @Override
                    public TransactionEventPublisher getIfUnique() {
                        return publisher;
                    }
                });
        l2.store.put("k", "v");
        l1.put("k", "v");
        cache.evict("k");
        assertThat(publisher.events).as("事务激活时经 TransactionEventPublisher 延迟（FR-003）")
                .extracting(e -> ((CacheEvictEvent) e).key()).containsExactly("k");
        assertThat(l2.store).as("提交前不删 L2").containsEntry("k", "v");
        // 提交后投递
        cache.onEvent(new CacheEvictEvent("k"));
        assertThat(l2.store).doesNotContainKey("k");
        assertThat(l1.getIfPresent("k")).isNull();
    }

    // ---------- FR-005 异常分支（6.5） ----------

    @Test
    void l2FailureDegradesToDirectLoadByDefault() {
        l2.failure = new IllegalStateException("redis down");
        TwoLevelCache cache = cache();
        assertThat(cache.get("k", String.class, this::load)).isEqualTo("value-1");
    }

    @Test
    void l2FailureFailsExplicitlyWhenConfigured() {
        properties.setL2Failure(L2FailurePolicy.FAIL);
        l2.failure = new IllegalStateException("redis down");
        TwoLevelCache cache = cache();
        assertThatThrownBy(() -> cache.get("k", String.class, this::load))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(CacheError.CACHE_UNAVAILABLE));
    }

    // ---------- AC-007 防击穿（合并回源） ----------

    @Test
    void penetrationGuardMergesConcurrentLoads() throws Exception {
        PenetrationGuard guard = new PenetrationGuard(true, 100, 2000, null);
        TwoLevelCache cache = new TwoLevelCache(l1, l2, bus, guard, properties, emptyPublisher());
        AtomicInteger concurrentLoads = new AtomicInteger();
        java.util.concurrent.CountDownLatch loadStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseLoad = new java.util.concurrent.CountDownLatch(1);
        Thread first = new Thread(() -> cache.get("hot", String.class, () -> {
            concurrentLoads.incrementAndGet();
            loadStarted.countDown();
            await(releaseLoad);
            return "loaded";
        }));
        first.start();
        assertThat(loadStarted.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread second = new Thread(() -> cache.get("hot", String.class, () -> {
            concurrentLoads.incrementAndGet();
            return "loaded-2";
        }));
        second.start();
        // 等待第二线程进入合并等待后放行首个回源
        Thread.sleep(100);
        releaseLoad.countDown();
        first.join(3000);
        second.join(3000);
        assertThat(concurrentLoads.get()).as("并发回源被合并（AC-007）").isEqualTo(1);
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
