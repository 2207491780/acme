package com.acme.framework.stream.support;

import com.acme.common.core.BizException;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.stream.config.StreamProperties;
import com.acme.framework.stream.internal.DedupStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等门（FR-014/AC-015、M-12：去重键规范、三态认领、fail-closed、失败释放）。
 */
class IdempotentGuardTest {

    static final class FakeStore implements DedupStore {

        Claim claimResult = Claim.CLAIMED;
        RuntimeException failure;
        final List<String> ops = new ArrayList<>();

        @Override
        public Claim tryBegin(String key, Duration processingTtl) {
            if (failure != null) {
                throw failure;
            }
            ops.add("begin:" + key + ":" + processingTtl);
            return claimResult;
        }

        @Override
        public void markDone(String key, Duration doneTtl) {
            ops.add("done:" + key + ":" + doneTtl);
        }

        @Override
        public void markFailed(String key) {
            ops.add("failed:" + key);
        }
    }

    private final FakeStore store = new FakeStore();
    private final IdempotentGuard guard =
            new IdempotentGuard(store, new KeyComposer("order-service"), new StreamProperties());

    @Test
    void dedupKeyCarriesServicePrefix() {
        assertThat(guard.dedupKey("m-1")).isEqualTo("order-service:m:dedup:m-1");
    }

    @Test
    void tryBeginClaimsWithProcessingTtl() {
        assertThat(guard.tryBegin("m-1")).isEqualTo(IdempotentGuard.Begin.STARTED);
        assertThat(store.ops).contains("begin:order-service:m:dedup:m-1:PT10M");
    }

    @Test
    void duplicateAndInFlightMapToDistinctResults() {
        store.claimResult = DedupStore.Claim.DUPLICATE;
        assertThat(guard.tryBegin("m-1")).isEqualTo(IdempotentGuard.Begin.DUPLICATE);
        store.claimResult = DedupStore.Claim.IN_FLIGHT;
        assertThat(guard.tryBegin("m-2")).isEqualTo(IdempotentGuard.Begin.IN_FLIGHT);
    }

    @Test
    void storeFailureFailsClosed() {
        store.failure = new IllegalStateException("down");
        assertThatThrownBy(() -> guard.tryBegin("m-1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(StreamError.DEDUP_STORE_UNAVAILABLE));
    }

    @Test
    void markDoneUsesCompletionTtl() {
        guard.markDone("m-1");
        assertThat(store.ops).contains("done:order-service:m:dedup:m-1:PT72H");
    }

    @Test
    void markFailedReleasesClaim() {
        guard.markFailed("m-1");
        assertThat(store.ops).contains("failed:order-service:m:dedup:m-1");
    }
}
