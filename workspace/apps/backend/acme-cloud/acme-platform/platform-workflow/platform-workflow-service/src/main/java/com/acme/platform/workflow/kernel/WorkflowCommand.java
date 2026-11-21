package com.acme.platform.workflow.kernel;

/**
 * 工作流命令接口（设计规格 §3.1 命令模式——所有状态变更唯一入口）。
 * 三段式契约：load（聚合装载，乐观锁重试轮次内重载）/ validate（状态机前置校验，无副作用）/
 * execute（节点行为与实体变更，变更经 {@link CommandContext} 持久化队列落库——单命令单事务，
 * 失败整体回滚无部分推进，AC-12）。
 *
 * @param <R> 命令返回类型
 */
public interface WorkflowCommand<R> {

    /** 聚合装载（重试轮次内重载：上次装载的实体在 STALE 后作废）。 */
    void load(CommandContext context);

    /** 状态机前置校验（Validation 拦截器位；失败抛 BizException 对应域错误码）。 */
    void validate(CommandContext context);

    /** 命令执行（节点行为推进 + 实体变更记录；返回值经 context.setResult 承载亦可）。 */
    R execute(CommandContext context);
}
