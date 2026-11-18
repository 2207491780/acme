package com.acme.framework.cache.interceptor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防击穿门（FR-007/AC-007、FR-007 异常分支：有限等待可判定、不无限阻塞）。
 */
class PenetrationGuardTest {

    @Test
    void disabledGuardPassesThrough() {
        PenetrationGuard guard = new PenetrationGuard(false, 10, 10, null);
        AtomicInteger loads = new AtomicInteger();
        assertThat(guard.guard("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(guard.guard("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v2");
        assertThat(loads).hasValue(2);
    }

    @Test
    void entryLockReleasedAfterExecution() {
        AtomicInteger releases = new AtomicInteger();
        AtomicReference<String> releasedToken = new AtomicReference<>();
        PenetrationGuard.EntryLock lock = new PenetrationGuard.EntryLock() {
            @Override
            public String tryLock(String key, long leaseMillis) {
                return "token-1";
            }

            @Override
            public void release(String key, String token) {
                releases.incrementAndGet();
                releasedToken.set(token);
            }
        };
        PenetrationGuard guard = new PenetrationGuard(true, 10, 10, lock);
        assertThat(guard.guard("k", () -> "ok")).isEqualTo("ok");
        assertThat(releases).hasValue(1);
        assertThat(releasedToken.get()).isEqualTo("token-1");
    }

    @Test
    void entryLockReleasedEvenWhenLoaderFails() {
        AtomicInteger releases = new AtomicInteger();
        PenetrationGuard.EntryLock lock = new PenetrationGuard.EntryLock() {
            @Override
            public String tryLock(String key, long leaseMillis) {
                return "token-1";
            }

            @Override
            public void release(String key, String token) {
                releases.incrementAndGet();
            }
        };
        PenetrationGuard guard = new PenetrationGuard(true, 10, 10, lock);
        try {
            guard.guard("k", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (RuntimeException ignored) {
            // 预期失败路径
        }
        assertThat(releases).hasValue(1);
    }

    @Test
    void waiterFallsBackToLoaderAfterBoundedWait() {
        PenetrationGuard.EntryLock denyLock = new PenetrationGuard.EntryLock() {
            @Override
            public String tryLock(String key, long leaseMillis) {
                return null;
            }

            @Override
            public void release(String key, String token) {
                // 未获锁不释放
            }
        };
        PenetrationGuard guard = new PenetrationGuard(true, 10, 20, denyLock);
        long start = System.nanoTime();
        assertThat(guard.guard("k", () -> "fallback")).isEqualTo("fallback");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).as("有限等待后放行，不无限阻塞（FR-007 异常分支）").isLessThan(2000);
    }
}
