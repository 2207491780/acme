package com.acme.framework.idempotent.internal;

import java.time.Duration;

/**
 * HTTP 幂等记录存储面（internal）：单 hash 记录三态（P 在途 / D 已完成）+ 结果缓存（FR-003），
 * 与 stream 去重键口径一致（P/D 三态、TTL）但实现自持——HTTP 需结果缓存、MQ 不需（架构设计 4.1）。
 */
public interface IdempotentRecordStore {

    /**
     * 认领（原子三态判定）：键不存在写 {@code s=P} + processingTtl 返回 {@link Claim#CLAIMED}；
     * {@code s=D} 返回 {@link Claim#DUPLICATE}；{@code s=P} 未过期返回 {@link Claim#IN_FLIGHT}。
     */
    Claim tryBegin(String key, Duration processingTtl);

    /**
     * 原子完成（FR-003 无「执行成功但记录丢失」窗口）：单键写 {@code s=D、r=resultJson} + resultTtl。
     */
    void markDone(String key, String resultJson, Duration resultTtl);

    /** 失败释放：仅删 {@code s=P} 占位（不触碰 {@code D}），允许按 10.3.3 重试。 */
    void markFailed(String key);

    /** 读取缓存结果（{@code r} 字段，缺失返回 null）。 */
    String findResult(String key);

    /** 认领结果。 */
    enum Claim {
        /** 首次认领成功。 */
        CLAIMED,
        /** 已完成重复请求。 */
        DUPLICATE,
        /** 在途未完成。 */
        IN_FLIGHT
    }
}
