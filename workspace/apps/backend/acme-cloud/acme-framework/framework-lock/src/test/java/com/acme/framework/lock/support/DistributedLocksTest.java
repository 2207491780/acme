package com.acme.framework.lock.support;

import com.acme.common.core.BizException;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.lock.config.LockProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁策略矩阵与失锁语义（FR-009~012、AC-009~012）。
 */
class DistributedLocksTest {

    /** 可编程 RLock 桩件。 */
    static final class FakeLock {

        final AtomicBoolean held = new AtomicBoolean();
        final AtomicBoolean acquireResult = new AtomicBoolean(true);
        final AtomicLong lastLeaseMillis = new AtomicLong();
        final AtomicInteger unlocks = new AtomicInteger();
        RuntimeException tryLockFailure;

        final RLock proxy = (RLock) Proxy.newProxyInstance(
                FakeLock.class.getClassLoader(), new Class<?>[] {RLock.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "tryLock":
                            if (tryLockFailure != null) {
                                throw tryLockFailure;
                            }
                            if (args != null && args.length == 3 && args[2] instanceof TimeUnit unit) {
                                lastLeaseMillis.set(unit.toMillis((Long) args[1]));
                            }
                            boolean ok = acquireResult.get();
                            held.set(ok);
                            return ok;
                        case "isHeldByCurrentThread":
                            return held.get();
                        case "unlock":
                            unlocks.incrementAndGet();
                            held.set(false);
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private final FakeLock lock = new FakeLock();
    private final LockProperties properties = new LockProperties();

    private DistributedLocks locks() {
        RedissonClient client = (RedissonClient) Proxy.newProxyInstance(
                DistributedLocksTest.class.getClassLoader(), new Class<?>[] {RedissonClient.class},
                (p, method, args) -> "getLock".equals(method.getName()) ? lock.proxy : null);
        return new DistributedLocks(client, new KeyComposer("order-service"), properties);
    }

    @Test
    void lockKeyCarriesServicePrefix() {
        assertThat(locks().lockKey("demo")).isEqualTo("order-service:l:demo");
    }

    @Test
    void acquiredLockRunsBodyAndReleases() {
        AtomicInteger runs = new AtomicInteger();
        String result = locks().execute("demo", LockFailurePolicy.FAIL_FAST, () -> {
            runs.incrementAndGet();
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(lock.unlocks).hasValue(1);
    }

    @Test
    void failFastThrowsWhenNotAcquired() {
        lock.acquireResult.set(false);
        assertThatThrownBy(() -> locks().execute("demo", LockFailurePolicy.FAIL_FAST, () -> "x"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(LockError.LOCK_ACQUIRE_FAILED));
    }

    @Test
    void waitPolicyPassesWaitBudget() {
        DistributedLocks locks = locks();
        locks.execute("demo", LockFailurePolicy.WAIT, 500, 0, () -> "ok");
        assertThat(lock.lastLeaseMillis.get()).as("缺省租期=看门狗（-1）").isEqualTo(-1L);
    }

    @Test
    void fixedLeaseOverridesWatchdog() {
        locks().execute("demo", LockFailurePolicy.FAIL_FAST, 0, 10, () -> "ok");
        assertThat(lock.lastLeaseMillis.get()).isEqualTo(10_000L);
    }

    @Test
    void skipPolicySkipsVoidBody() {
        lock.acquireResult.set(false);
        AtomicBoolean ran = new AtomicBoolean();
        locks().execute("demo", LockFailurePolicy.SKIP, () -> ran.set(true));
        assertThat(ran).isFalse();
    }

    @Test
    void skipPolicyRejectsNonVoid() {
        assertThatThrownBy(() -> locks().execute("demo", LockFailurePolicy.SKIP, () -> "x"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(LockError.LOCK_ACQUIRE_FAILED));
    }

    @Test
    void lockLossThrowsLockLost() {
        assertThatThrownBy(() -> locks().execute("demo", LockFailurePolicy.FAIL_FAST, () -> {
            lock.held.set(false);
            return "done";
        })).isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(LockError.LOCK_LOST));
    }

    @Test
    void redisFailureMapsToLockUnavailable() {
        lock.tryLockFailure = new IllegalStateException("redis down");
        assertThatThrownBy(() -> locks().execute("demo", LockFailurePolicy.FAIL_FAST, () -> "x"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(LockError.LOCK_UNAVAILABLE));
    }

    @Test
    void bodyFailureReleasesLockAndPropagates() {
        assertThatThrownBy(() -> locks().execute("demo", LockFailurePolicy.FAIL_FAST, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(lock.unlocks).hasValue(1);
    }
}
