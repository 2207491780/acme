package com.acme.framework.core.support;

import org.springframework.core.task.TaskDecorator;

/**
 * 上下文透传任务装饰器（FR-002）：受管线程池默认装配（架构设计 4.1/4.2）。
 */
public class ContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return ContextPropagators.wrap(runnable);
    }
}
