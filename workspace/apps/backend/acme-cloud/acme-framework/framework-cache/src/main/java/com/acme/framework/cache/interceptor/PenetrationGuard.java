package com.acme.framework.cache.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 防击穿门（FR-007/AC-007）：单 JVM 回源合并（单飞）+ 可选 Redis 入口锁（自持 Redis，不依赖 framework-lock——Modules.md 4.3）；
 * 未获权方有限等待后放行回源，超时可判定、不无限阻塞（FR-007 异常分支）。
 */
public class PenetrationGuard {

    private static final Logger log = LoggerFactory.getLogger(PenetrationGuard.class);

    private final boolean enabled;
    private final long leaseMillis;
    private final long waitMillis;
    private final EntryLock entryLock;
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public PenetrationGuard(boolean enabled, long leaseMillis, long waitMillis, EntryLock entryLock) {
        this.enabled = enabled;
        this.leaseMillis = leaseMillis;
        this.waitMillis = waitMillis;
        this.entryLock = entryLock;
    }

    /** 执行受保护回源（单飞合并；跨实例入口锁可选）。 */
    @SuppressWarnings("unchecked")
    public <T> T guard(String key, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        CompletableFuture<Object> own = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, own);
        if (existing != null) {
            try {
                return (T) existing.get(Math.max(waitMillis, 1), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                // 有限等待后放行回源（不无限阻塞，FR-007 异常分支）
                log.debug("防击穿等待超时，放行回源（FR-007）: {}", key);
                return loader.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return loader.get();
            } catch (Exception ex) {
                return loader.get();
            }
        }
        try {
            return withEntryLock(key, loader, own);
        } finally {
            inFlight.remove(key, own);
        }
    }

    private <T> T withEntryLock(String key, Supplier<T> loader, CompletableFuture<Object> own) {
        if (entryLock == null) {
            T value = loader.get();
            own.complete(value);
            return value;
        }
        String token = entryLock.tryLock(key, leaseMillis);
        try {
            if (token == null) {
                // 未获权方：有限等待后放行回源
                sleepQuietly(waitMillis);
            }
            T value = loader.get();
            own.complete(value);
            return value;
        } catch (RuntimeException ex) {
            own.completeExceptionally(ex);
            throw ex;
        } finally {
            if (token != null) {
                entryLock.release(key, token);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 入口锁抽象（internal Redis SET NX + token 实现；测试可注入假件）。 */
    public interface EntryLock {

        /**
         * 尝试加锁；成功返回持有者 token，失败返回 null（不抛异常即视为 Redis 可用性边界内）。
         */
        String tryLock(String key, long leaseMillis);

        /** 释放锁（token 比对，非持有者/租约过期不误删他实例锁）。 */
        void release(String key, String token);
    }
}
