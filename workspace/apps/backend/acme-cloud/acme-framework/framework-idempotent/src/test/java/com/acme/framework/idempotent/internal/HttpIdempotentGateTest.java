package com.acme.framework.idempotent.internal;

import com.acme.common.core.BizException;
import com.acme.common.serialization.Json;
import com.acme.framework.idempotent.config.IdempotentProperties;
import com.acme.framework.idempotent.support.IdempotentError;
import com.acme.framework.idempotent.support.InFlightPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP 幂等状态机测试（FR-001/003、AC-001/002：认领/完成/释放/在途/回放/过期语义）。
 */
class HttpIdempotentGateTest {

    private final InMemoryIdempotentRecordStore store = new InMemoryIdempotentRecordStore();
    private final ResultCodec codec = new JsonResultCodec(new Json(JsonMapper.builder().build()));
    private final HttpIdempotentGate gate = new HttpIdempotentGate(store, codec, properties());

    private static IdempotentProperties properties() {
        IdempotentProperties properties = new IdempotentProperties();
        properties.setInFlightWait(Duration.ofMillis(300));
        return properties;
    }

    @Test
    void firstRequestExecutesAndCachesResult() {
        AtomicInteger calls = new AtomicInteger();
        Callable<String> action = () -> {
            calls.incrementAndGet();
            return "ok";
        };
        assertThat(gate.execute("k1", action, String.class, InFlightPolicy.WAIT)).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void duplicateReplaysCachedResultWithoutReexecution() {
        AtomicInteger calls = new AtomicInteger();
        Callable<String> action = () -> {
            calls.incrementAndGet();
            return "ok-" + calls.get();
        };
        gate.execute("k2", action, String.class, InFlightPolicy.WAIT);
        assertThat(gate.execute("k2", action, String.class, InFlightPolicy.WAIT)).isEqualTo("ok-1");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void failureReleasesClaimAndAllowsRetry() {
        AtomicInteger calls = new AtomicInteger();
        Callable<String> failing = () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> gate.execute("k3", failing, String.class, InFlightPolicy.WAIT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(gate.execute("k3", () -> "recovered", String.class, InFlightPolicy.WAIT)).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void inFlightRejectFailsFastWith409() {
        store.tryBegin("k4", Duration.ofSeconds(60));
        assertThatThrownBy(() -> gate.execute("k4", () -> "x", String.class, InFlightPolicy.REJECT))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(IdempotentError.IN_FLIGHT));
    }

    @Test
    void inFlightWaitTakesCompletedResult() throws Exception {
        store.tryBegin("k5", Duration.ofSeconds(60));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> completer = executor.submit(() -> {
                Thread.sleep(100);
                store.markDone("k5", "\"done\"", Duration.ofMinutes(5));
                return null;
            });
            Callable<String> shouldNotRun = () -> {
                throw new IllegalStateException("不应执行");
            };
            assertThat(gate.execute("k5", shouldNotRun, String.class, InFlightPolicy.WAIT)).isEqualTo("done");
            completer.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void inFlightWaitTimesOutWithInFlightError() {
        store.tryBegin("k6", Duration.ofSeconds(60));
        assertThatThrownBy(() -> gate.execute("k6", () -> "x", String.class, InFlightPolicy.WAIT))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(IdempotentError.IN_FLIGHT));
    }

    @Test
    void storeFailureIsFailClosed() {
        IdempotentRecordStore broken = new InMemoryIdempotentRecordStore() {
            @Override
            public Claim tryBegin(String key, Duration processingTtl) {
                throw new IllegalStateException("redis down");
            }
        };
        HttpIdempotentGate failClosed = new HttpIdempotentGate(broken, codec, properties());
        assertThatThrownBy(() -> failClosed.execute("k7", () -> "x", String.class, InFlightPolicy.WAIT))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(IdempotentError.STORE_UNAVAILABLE));
    }

    @Test
    void concurrentSameKeyRunsExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Callable<String> action = () -> {
                start.await();
                calls.incrementAndGet();
                Thread.sleep(50);
                return "once";
            };
            @SuppressWarnings("unchecked")
            Future<String>[] futures = new Future[4];
            for (int i = 0; i < 4; i++) {
                futures[i] = executor.submit(() -> gate.execute("k8", action, String.class, InFlightPolicy.WAIT));
            }
            start.countDown();
            int successes = 0;
            for (Future<String> future : futures) {
                try {
                    if ("once".equals(future.get())) {
                        successes++;
                    }
                } catch (Exception ignored) {
                    // 在途/超时视为未取得结果
                }
            }
            assertThat(calls.get()).isEqualTo(1);
            assertThat(successes).isGreaterThanOrEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void complexResultRoundTrips() {
        Map<String, Object> payload = Map.of("id", 1, "name", "n");
        assertThat(gate.execute("k9", () -> payload, Map.class, InFlightPolicy.WAIT))
                .containsEntry("name", "n");
        assertThat(gate.execute("k9", () -> Map.of(), Map.class, InFlightPolicy.WAIT))
                .containsEntry("name", "n");
    }
}
