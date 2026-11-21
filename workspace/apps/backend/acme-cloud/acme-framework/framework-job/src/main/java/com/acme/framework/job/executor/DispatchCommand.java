package com.acme.framework.job.executor;

import java.time.Instant;

/**
 * 下发指令接 收镜像（REQ-20261121-200724 确认⑤；JOB-1 契约面 JobDispatchCommand 字段镜像）——
 * framework-job 不依赖 platform 模块（分层 BR-001），wire 字段名与 platform-scheduler-api
 * {@code JobDispatchCommand} 一致，字段名/路径不得变更。
 */
public record DispatchCommand(String jobKey, String handler, String param,
                              Shard shardContext, Instant fireTime,
                              String traceId, int attempt) {

    /** 分片上下文镜像（{index,total}；本 REQ 单分片语义 total=1）。 */
    public record Shard(int index, int total) {

        /** 单分片缺省上下文。 */
        public static Shard single() {
            return new Shard(0, 1);
        }
    }
}
