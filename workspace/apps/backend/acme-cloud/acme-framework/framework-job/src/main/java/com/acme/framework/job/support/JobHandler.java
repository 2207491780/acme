package com.acme.framework.job.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 调度任务声明（REQ-20261121-200724 确认③）：标记「可被调度中心调度的任务」——启动收集进
 * {@link TaskRegistry} 兼容视图（本地 TaskTrigger 触发消费）与 {@link JobHandlerRegistry} 执行器清单，
 * 并接收调度中心下发指令（framework-job 执行器 SDK 自动装配）。
 *
 * <p>与 {@link AcmeTask} 的关系：两注解共存过渡（删除归后续清理 REQ）。同一方法不得同时标注
 * 两注解（收集期显式失败）；本地语义（本地 {@code @Scheduled} 兜底 + {@link TaskTrigger} 手动触发 +
 * 互斥锁）两注解一致。</p>
 *
 * <p>执行器下发执行的方法参数解析（按类型）：{@code String} → 触发来源
 * （下发执行恒为 {@code SCHEDULER_DISPATCH}）；{@link JobExecutionContext} → 下发上下文
 * （attempt/shard 透传，确认⑤）。未支持的参数类型在下发执行期显式失败。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface JobHandler {

    /** 任务键（集群内唯一；注册表 key、锁 key 段与调度中心 job_key 沿用同值）。 */
    String jobKey();

    /** 单次执行超时毫秒（下发执行超时=TIMEOUT 上报 + 方法线程中断尽力，确认⑤）。 */
    long timeoutMs() default 60_000;

    /** 是否可分片（分片参数经 {@link JobExecutionContext} 可见；真分片扇出归 JOB-4）。 */
    boolean shardable() default false;

    /** 任务描述（调度中心任务定义 remark / 管理页展示）。 */
    String description() default "";
}
