package com.acme.framework.stream.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三态去重语义（M-12 不丢首投）：D 重复跳过、P 在途跳过、P 过期后可重投再认领、失败释放可再认领。
 * 以时钟可拨的内存假件锁定 RedisDedupStore Lua 应满足的契约（Redis 侧为单键 GET/SET NX/DEL 原子脚本）。
 */
class ThreeStateDedupSemanticsTest {

    /** 带 TTL 的内存三态假件（模拟 RedisDedupStore Lua 契约）。 */
    static final class TtlFakeStore implements DedupStore {

        record Entry(String value, Instant expireAt) {
        }

        final Map<String, Entry> store = new ConcurrentHashMap<>();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public Claim tryBegin(String key, Duration processingTtl) {
            Entry entry = liveEntry(key);
            if (entry != null && "D".equals(entry.value())) {
                return Claim.DUPLICATE;
            }
            if (entry != null && "P".equals(entry.value())) {
                return Claim.IN_FLIGHT;
            }
            store.put(key, new Entry("P", now.plus(processingTtl)));
            return Claim.CLAIMED;
        }

        @Override
        public void markDone(String key, Duration doneTtl) {
            store.put(key, new Entry("D", now.plus(doneTtl)));
        }

        @Override
        public void markFailed(String key) {
            Entry entry = liveEntry(key);
            if (entry != null && "P".equals(entry.value())) {
                store.remove(key);
            }
        }

        private Entry liveEntry(String key) {
            Entry entry = store.get(key);
            if (entry == null) {
                return null;
            }
            if (!entry.expireAt().isAfter(now)) {
                store.remove(key);
                return null;
            }
            return entry;
        }
    }

    private final TtlFakeStore store = new TtlFakeStore();
    private final Duration processingTtl = Duration.ofMinutes(10);
    private final Duration doneTtl = Duration.ofHours(72);

    @Test
    void doneStateSkipsAsDuplicate() {
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.CLAIMED);
        store.markDone("k", doneTtl);
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.DUPLICATE);
    }

    @Test
    void processingStateSkipsAsInFlight() {
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.CLAIMED);
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.IN_FLIGHT);
    }

    @Test
    void crashWithoutMarkDoneAllowsReclaimAfterProcessingTtl() {
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.CLAIMED);
        // 崩溃：不 markDone / markFailed → P 残留
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.IN_FLIGHT);
        store.now = store.now.plus(processingTtl).plusSeconds(1);
        assertThat(store.tryBegin("k", processingTtl))
                .as("P 过期后可重投再认领（M-12 不丢首投）").isEqualTo(DedupStore.Claim.CLAIMED);
    }

    @Test
    void markFailedReleasesProcessingOnly() {
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.CLAIMED);
        store.markFailed("k");
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.CLAIMED);
    }

    @Test
    void markFailedDoesNotRemoveDoneState() {
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.CLAIMED);
        store.markDone("k", doneTtl);
        store.markFailed("k");
        assertThat(store.tryBegin("k", processingTtl)).isEqualTo(DedupStore.Claim.DUPLICATE);
    }
}
