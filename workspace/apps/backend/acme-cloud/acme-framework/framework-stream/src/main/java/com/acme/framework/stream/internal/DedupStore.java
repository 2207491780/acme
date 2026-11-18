package com.acme.framework.stream.internal;

import java.time.Duration;

/**
 * 去重存储面（internal）：Redis 去重键三态实现可测替换（Q-002；FR-014 fail-closed 语义在 IdempotentGuard）。
 * 三态口径（M-12 不丢首投）：{@code P}=在途占位（短 TTL，崩溃后过期可重投再认领）、{@code D}=已完成（长 TTL 去重）。
 */
public interface DedupStore {

    /**
     * 认领（原子三态判定）：键不存在写 {@code P} + processingTtl 返回 {@link Claim#CLAIMED}；
     * 存在 {@code D} 返回 {@link Claim#DUPLICATE}；存在 {@code P} 未过期返回 {@link Claim#IN_FLIGHT}；
     * {@code P} 过期后键已消失，可再认领。
     */
    Claim tryBegin(String key, Duration processingTtl);

    /** 完成标记：写 {@code D} + doneTtl（去重窗口按完成 TTL 计）。 */
    void markDone(String key, Duration doneTtl);

    /** 失败释放：仅删 {@code P} 占位（不触碰 {@code D}），binder 重投可再处理。 */
    void markFailed(String key);

    /** 认领结果。 */
    enum Claim {
        /** 首投认领成功。 */
        CLAIMED,
        /** 已完成重复投递。 */
        DUPLICATE,
        /** 在途未完成（占位未过期）。 */
        IN_FLIGHT
    }
}
