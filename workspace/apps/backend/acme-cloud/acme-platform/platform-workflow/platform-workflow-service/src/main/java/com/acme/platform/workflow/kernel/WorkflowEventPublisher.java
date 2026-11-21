package com.acme.platform.workflow.kernel;

/**
 * EventPublish SPI（设计规格 §3.6 扩展点；REQ-20261121-142316 交付边界：本 REQ 仅 SPI 钩子 +
 * logging no-op 实现，Kafka 事件流归 BPM-5）。发布时序=AFTER_COMMIT 异步（拦截器承载），
 * 事务回滚不发布（AC-12 一致性面）。
 */
public interface WorkflowEventPublisher {

    /** 发布单条领域事件（实现须自行消化异常——事件发布失败不反噬命令结果）。 */
    void publish(WorkflowEvent event);
}
