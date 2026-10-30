package com.acme.framework.stream.support;

import com.acme.common.core.BizException;
import com.acme.framework.core.support.KeyComposer;
import com.acme.framework.stream.config.StreamProperties;
import com.acme.framework.stream.internal.DedupStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 消费幂等门（FR-014/AC-015、M-12）：Redis 去重键三态（P 在途 / D 已完成），不可关闭（取舍 #5）；
 * 去重存储不可用 fail-closed 进重试/死信（FR-014 异常分支），不静默重复处理。
 * 三态口径保证不丢首投：崩溃残留 P 短 TTL 过期后可重投再认领。
 */
public class IdempotentGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotentGuard.class);

    private final DedupStore store;
    private final KeyComposer keyComposer;
    private final StreamProperties properties;

    public IdempotentGuard(DedupStore store, KeyComposer keyComposer, StreamProperties properties) {
        this.store = store;
        this.keyComposer = keyComposer;
        this.properties = properties;
    }

    /** 去重键规范（服务前缀，Backend.md 12.3.2）。 */
    public String dedupKey(String idempotentKey) {
        return keyComposer.compose("m", "dedup", idempotentKey);
    }

    /**
     * 尝试认领（首投 {@link Begin#STARTED}；重复/在途跳过）。
     *
     * @throws BizException 去重存储不可用（DEDUP_STORE_UNAVAILABLE，fail-closed）
     */
    public Begin tryBegin(String idempotentKey) {
        try {
            DedupStore.Claim claim = store.tryBegin(dedupKey(idempotentKey),
                    properties.getIdempotency().getProcessingTtl());
            return switch (claim) {
                case CLAIMED -> Begin.STARTED;
                case DUPLICATE -> Begin.DUPLICATE;
                case IN_FLIGHT -> Begin.IN_FLIGHT;
            };
        } catch (RuntimeException ex) {
            throw new BizException(StreamError.DEDUP_STORE_UNAVAILABLE, "去重存储不可用", ex);
        }
    }

    /** 成功标记（写 D + 完成 TTL；失败 ERROR 兜底，不影响已成功的业务结果）。 */
    public void markDone(String idempotentKey) {
        try {
            store.markDone(dedupKey(idempotentKey), Duration.ofHours(Math.max(properties.getIdempotency().getTtlHours(), 1)));
        } catch (RuntimeException ex) {
            log.error("去重键完成标记失败（TTL 兜底过期）: {}", ex.getMessage());
        }
    }

    /** 失败释放（仅删 P 占位，允许 binder 重投再处理，10.3.3 重试语义）。 */
    public void markFailed(String idempotentKey) {
        try {
            store.markFailed(dedupKey(idempotentKey));
        } catch (RuntimeException ex) {
            log.error("去重键释放失败（TTL 兜底过期）: {}", ex.getMessage());
        }
    }

    /** 认领结果（供消费管线区分重复/在途日志面）。 */
    public enum Begin {
        /** 首投认领成功。 */
        STARTED,
        /** 已完成重复投递。 */
        DUPLICATE,
        /** 在途未完成（占位未过期）。 */
        IN_FLIGHT
    }
}
