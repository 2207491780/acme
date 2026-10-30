package com.acme.framework.lock.support;

import com.acme.common.core.BizException;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.lock.config.LockProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 编程式锁门面（FR-009/011/012、AC-009~012）：跨实例互斥、看门狗租约（缺省 lease）、失败策略；
 * 临界区结束校验仍持有，失锁抛 LOCK_LOST + 结构化 ERROR（7.2）；Redis 不可用显式可感知（LOCK_UNAVAILABLE）。
 */
public class DistributedLocks {

    private static final Logger log = LoggerFactory.getLogger(DistributedLocks.class);

    /** 看门狗模式租期哨兵（Redisson 语义：leaseTime=-1 自动续租）。 */
    private static final long WATCHDOG_LEASE = -1L;

    private final RedissonClient redissonClient;
    private final KeyComposer keyComposer;
    private final LockProperties properties;

    public DistributedLocks(RedissonClient redissonClient, KeyComposer keyComposer, LockProperties properties) {
        this.redissonClient = redissonClient;
        this.keyComposer = keyComposer;
        this.properties = properties;
    }

    /** 规范锁 key（FR 锁 key 规范：{服务}:l:{lockName}，架构设计 7.4）。 */
    public String lockKey(String lockName) {
        return keyComposer.compose("l", lockName);
    }

    /** 互斥执行（配置默认策略/等待/看门狗租期）。 */
    public <T> T execute(String lockName, LockFailurePolicy policy, Supplier<T> body) {
        return execute(lockName, policy, 0, 0, body);
    }

    /** 互斥执行（void；SKIP 策略合法路径）。 */
    public void execute(String lockName, LockFailurePolicy policy, Runnable body) {
        execute(lockName, policy, 0, 0, body);
    }

    /**
     * 互斥执行（显式等待/租期覆盖；0=取默认）。
     *
     * @throws BizException SKIP 策略遇非 void 路径显式失败（FR-012 取舍 #8）
     */
    public <T> T execute(String lockName, LockFailurePolicy policy, long waitMillisOverride, long leaseSeconds,
                         Supplier<T> body) {
        LockFailurePolicy effective = policy == null ? properties.getDefaultPolicy() : policy;
        if (effective == LockFailurePolicy.SKIP) {
            // SKIP 仅限 void 方法（取舍 #8）：非 void 声明即显式失败
            throw new BizException(LockError.LOCK_ACQUIRE_FAILED, "SKIP 策略仅限 void 方法");
        }
        Holder<T> holder = new Holder<>();
        runLocked(lockName, effective, waitMillisOverride, leaseSeconds,
                () -> holder.value = body.get(), false);
        return holder.value;
    }

    /** 互斥执行（void 路径；SKIP=未获锁跳过执行，绝不无锁并发）。 */
    public void execute(String lockName, LockFailurePolicy policy, long waitMillisOverride, long leaseSeconds,
                        Runnable body) {
        LockFailurePolicy effective = policy == null ? properties.getDefaultPolicy() : policy;
        runLocked(lockName, effective, waitMillisOverride, leaseSeconds, body, true);
    }

    private void runLocked(String lockName, LockFailurePolicy policy, long waitMillisOverride, long leaseSeconds,
                           Runnable body, boolean voidMethod) {
        String key = lockKey(lockName);
        RLock lock = redissonClient.getLock(key);
        long waitMillis = waitMillisOverride > 0 ? waitMillisOverride
                : (policy == LockFailurePolicy.WAIT ? properties.getWaitMillis() : 0);
        long leaseMillis = leaseSeconds > 0 ? leaseSeconds * 1000L : WATCHDOG_LEASE;
        boolean acquired;
        try {
            acquired = lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(LockError.LOCK_ACQUIRE_FAILED, "锁等待被中断", ex);
        } catch (RuntimeException ex) {
            // Redis 不可用显式可感知（FR-009 异常分支），不无锁静默并发
            throw new BizException(LockError.LOCK_UNAVAILABLE, "分布式锁服务不可用", ex);
        }
        if (!acquired) {
            if (policy == LockFailurePolicy.SKIP && voidMethod) {
                log.info("锁未获取，跳过执行（FR-012 SKIP）: {}", key);
                return;
            }
            throw new BizException(LockError.LOCK_ACQUIRE_FAILED, "分布式锁获取失败: " + lockName);
        }
        boolean bodyOk = false;
        try {
            body.run();
            bodyOk = true;
        } finally {
            // 临界区结束校验仍持有（FR-011 异常分支：失锁告警，不静默双持有）
            boolean stillHeld = lock.isHeldByCurrentThread();
            if (!stillHeld) {
                log.error("执行期间分布式锁丢失（FR-011）: {}", key);
            }
            if (stillHeld) {
                try {
                    lock.unlock();
                } catch (RuntimeException ex) {
                    log.warn("锁释放失败已忽略（异常路径仍释放，FR-010）: {}", ex.getMessage());
                }
            }
            if (!stillHeld && bodyOk) {
                throw new BizException(LockError.LOCK_LOST, "执行期间分布式锁已丢失: " + lockName);
            }
        }
    }

    private static final class Holder<T> {
        private T value;
    }
}
