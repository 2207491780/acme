package com.acme.platform.scheduler.api;

/**
 * 分片上下文（契约确认⑤下发 body 字段 shardContext{index,total}；设计规格 §5 分片透传）。
 * 本 REQ（JOB-1）默认单分片语义：total=shard_total（默认 1）、index=0；按实例轮询的真分片扇出归 JOB-4。
 *
 * @param index 分片序号（0 起）
 * @param total 分片总数
 */
public record ShardContext(int index, int total) {

    /** 单分片缺省上下文（index=0, total=1）。 */
    public static ShardContext single() {
        return new ShardContext(0, 1);
    }
}
