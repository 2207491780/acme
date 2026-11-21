package com.acme.platform.workflow.kernel;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 命令上下文（责任链承载面——设计规格 §3.1）：调用载荷 + 命令内时刻 + 持久化队列（Persistence
 * 拦截器位同事务依次执行）+ 领域事件收集（EventPublish 拦截器位 AFTER_COMMIT 异步发布）。
 */
public final class CommandContext {

    private final WorkflowCommand<?> command;
    private final Object payload;
    private final Instant now;
    /** 持久化队列（Persistence 拦截器位收口；引擎执行段为直写语义——本队列承载命令级残余操作）。 */
    private final Deque<Runnable> persistenceOps = new ArrayDeque<>();
    private final List<WorkflowEvent> events = new ArrayList<>();
    private Object result;

    public CommandContext(WorkflowCommand<?> command, Object payload, Instant now) {
        this.command = command;
        this.payload = payload;
        this.now = now;
    }

    /** 执行中命令（拦截器驱动 load/validate/execute 三段式）。 */
    public WorkflowCommand<?> command() {
        return command;
    }

    /** 命令内统一时刻（common-core Clock 口径，FR-019）。 */
    public Instant now() {
        return now;
    }

    /** 调用载荷（类型化取值）。 */
    public <P> P payload(Class<P> type) {
        return type.cast(payload);
    }

    /**
     * 持久化操作（直写语义）：登记即在当前命令事务内执行——命令失败整体回滚（AC-12），
     * 同命令内后续查询「读己之写」（并行 join 计数/全集取消依赖当前状态一致面）。
     * Persistence 拦截器位保留为事务收口与残余队列排空（直写后队列常态为空）。
     */
    public void persist(Runnable operation) {
        operation.run();
    }

    /** 取出全部持久化操作（拦截器专用）。 */
    public Deque<Runnable> drainPersistenceOps() {
        return persistenceOps;
    }

    /** 记录领域事件（AFTER_COMMIT 异步发布；本事务回滚则不发布——AC-12 一致性面）。 */
    public void recordEvent(WorkflowEvent event) {
        events.add(event);
    }

    /** 取事件全集（拦截器专用）。 */
    public List<WorkflowEvent> events() {
        return List.copyOf(events);
    }

    /** 清空事件（乐观锁重试每轮清空——重跑 execute 段重录，防重复发布；拦截器专用）。 */
    public void clearEvents() {
        events.clear();
    }

    /** 命令返回值。 */
    @SuppressWarnings("unchecked")
    public <R> R result() {
        return (R) result;
    }

    public void setResult(Object result) {
        this.result = result;
    }
}
