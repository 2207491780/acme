package com.acme.framework.idempotent.internal;

import com.acme.common.core.BizException;
import com.acme.framework.idempotent.config.IdempotentProperties;
import com.acme.framework.idempotent.support.IdempotentError;
import com.acme.framework.idempotent.support.InFlightPolicy;

import java.util.concurrent.Callable;

/**
 * HTTP 幂等门（FR-001/003、AC-001/002）：认领 → 执行 → 原子完成（结果缓存）→ 重复同键回放缓存结果；
 * 业务失败释放认领（10.3.3 重试语义）；存储不可用 fail-closed（SYS-11-0003，不提供 fail-open）。
 */
public class HttpIdempotentGate {

    private final IdempotentRecordStore store;
    private final ResultCodec codec;
    private final IdempotentProperties properties;

    public HttpIdempotentGate(IdempotentRecordStore store, ResultCodec codec, IdempotentProperties properties) {
        this.store = store;
        this.codec = codec;
        this.properties = properties;
    }

    /**
     * 以幂等键执行（FR-001）。
     *
     * @param key        幂等键（token 或业务键）
     * @param action     业务执行
     * @param returnType 返回类型（回放缓存结果反序列化用）
     */
    public <T> T execute(String key, Callable<T> action, Class<T> returnType, InFlightPolicy inFlightPolicy) {
        IdempotentRecordStore.Claim claim;
        try {
            claim = store.tryBegin(key, properties.getClaimProcessingTtl());
        } catch (RuntimeException ex) {
            // fail-closed：存储不可用拒绝执行（FR-001 不得静默放行导致重复执行）
            throw new BizException(IdempotentError.STORE_UNAVAILABLE, "幂等服务暂不可用", ex);
        }
        switch (claim) {
            case CLAIMED -> {
                try {
                    T result = action.call();
                    complete(key, result);
                    return result;
                } catch (RuntimeException ex) {
                    release(key);
                    throw ex instanceof BizException ? ex : new IllegalStateException("业务执行失败", ex);
                } catch (Exception ex) {
                    release(key);
                    throw new IllegalStateException("业务执行失败", ex);
                }
            }
            case DUPLICATE -> {
                return replay(key, returnType);
            }
            default -> {
                return await(key, returnType, inFlightPolicy);
            }
        }
    }

    /** 原子完成（FR-003）：写失败视为记录丢失风险 → fail-closed 报错，不静默当成功。 */
    private void complete(String key, Object result) {
        try {
            store.markDone(key, codec.encode(result), properties.getResultTtl());
        } catch (RuntimeException ex) {
            throw new BizException(IdempotentError.STORE_UNAVAILABLE, "幂等记录写入失败", ex);
        }
    }

    /** 失败释放（允许按 10.3.3 重试）；释放失败 TTL 兜底过期，ERROR 由调用方日志兜底。 */
    private void release(String key) {
        try {
            store.markFailed(key);
        } catch (RuntimeException ignored) {
            // TTL 兜底过期（FR-003）
        }
    }

    /** 回放缓存结果（AC-001）。 */
    private <T> T replay(String key, Class<T> returnType) {
        try {
            String json = store.findResult(key);
            return json == null ? null : codec.decode(json, returnType);
        } catch (RuntimeException ex) {
            throw new BizException(IdempotentError.STORE_UNAVAILABLE, "幂等服务暂不可用", ex);
        }
    }

    /** 在途处置（Q-D002）：WAIT 有界等待取结果，超时转在途错误；REJECT 快速失败。 */
    private <T> T await(String key, Class<T> returnType, InFlightPolicy inFlightPolicy) {
        InFlightPolicy policy = inFlightPolicy == null ? properties.getInFlightPolicy() : inFlightPolicy;
        if (policy == InFlightPolicy.REJECT) {
            throw new BizException(IdempotentError.IN_FLIGHT);
        }
        long deadline = System.currentTimeMillis() + Math.max(properties.getInFlightWait().toMillis(), 0);
        while (System.currentTimeMillis() < deadline) {
            try {
                String json = store.findResult(key);
                if (json != null) {
                    return codec.decode(json, returnType);
                }
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BizException(IdempotentError.IN_FLIGHT);
            } catch (RuntimeException ex) {
                throw new BizException(IdempotentError.STORE_UNAVAILABLE, "幂等服务暂不可用", ex);
            }
        }
        throw new BizException(IdempotentError.IN_FLIGHT);
    }
}
