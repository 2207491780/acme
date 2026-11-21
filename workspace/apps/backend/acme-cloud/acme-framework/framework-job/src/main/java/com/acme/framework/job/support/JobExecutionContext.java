package com.acme.framework.job.support;

import java.time.Instant;

/**
 * 下发执行上下文（REQ-20261121-200724 确认⑤）：调度中心下发执行时透传至 {@code @JobHandler}
 * 方法（声明该类型参数即注入，attempt/shard 透传面）； Ambient 读取经 {@link #current()}。
 * 框架层镜像 scheduler 契约面的下发 body 字段（不依赖 platform 模块，分层 BR-001）。
 *
 * @param executionId 调度中心执行记录 id（sch_job_execution.id）
 * @param jobKey      任务键
 * @param handler     执行器侧 handler 标识
 * @param param       任务参数（定义 jsonb 文本原样透传，可空）
 * @param fireTime    计划触发时刻
 * @param traceId     链路追踪 id
 * @param attempt     重试轮次（0=首发）
 * @param shardIndex  分片序号（0 起）
 * @param shardTotal  分片总数
 * @param triggerType 触发来源（下发执行恒为 {@code SCHEDULER_DISPATCH}）
 */
public record JobExecutionContext(Long executionId, String jobKey, String handler, String param,
                                 Instant fireTime, String traceId, int attempt,
                                 int shardIndex, int shardTotal, String triggerType) {

    private static final ThreadLocal<JobExecutionContext> CURRENT = new ThreadLocal<>();

    /** 当前执行上下文（非下发执行场景为 null）。 */
    public static JobExecutionContext current() {
        return CURRENT.get();
    }

    /** 绑定上下文（SDK 下发执行线程内使用；结束须 {@link #clear()}）。 */
    public static void bind(JobExecutionContext context) {
        CURRENT.set(context);
    }

    /** 清理上下文。 */
    public static void clear() {
        CURRENT.remove();
    }
}
