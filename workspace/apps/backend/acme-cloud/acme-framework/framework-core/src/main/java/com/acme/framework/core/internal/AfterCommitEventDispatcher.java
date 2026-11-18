package com.acme.framework.core.internal;

import com.acme.framework.core.support.AfterCommitEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件投递器（FR-003）：泛型匹配（DQ-001）、同步投递（取舍 4）、逐监听 try/catch 记日志吞掉（BR-019）。
 */
public class AfterCommitEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitEventDispatcher.class);

    private final List<ListenerBinding> bindings;

    public AfterCommitEventDispatcher(List<AfterCommitEventListener<?>> listeners) {
        List<ListenerBinding> resolved = new ArrayList<>();
        if (listeners != null) {
            for (AfterCommitEventListener<?> listener : listeners) {
                resolved.add(new ListenerBinding(listener, resolveEventType(listener)));
            }
        }
        this.bindings = List.copyOf(resolved);
    }

    /** 投递事件（BR-013/BR-017 调用时机由发布方决定；本方法仅按序投递并隔离异常）。 */
    public void dispatch(Object event) {
        if (event == null) {
            return;
        }
        for (ListenerBinding binding : bindings) {
            if (!binding.matches(event)) {
                continue;
            }
            try {
                binding.deliver(event);
            } catch (Throwable ex) {
                log.error("事务提交后事件监听执行异常（BR-019）", ex);
            }
        }
    }

    /** 泛型订阅类型解析（DQ-001）；不可解析时按 Object 兜底投递。 */
    private static Class<?> resolveEventType(AfterCommitEventListener<?> listener) {
        ResolvableType type = ResolvableType.forInstance(listener).as(AfterCommitEventListener.class);
        if (type == ResolvableType.NONE) {
            type = ResolvableType.forClass(AfterCommitEventListener.class, listener.getClass());
        }
        Class<?> eventType = type.getGeneric(0).resolve();
        return eventType == null ? Object.class : eventType;
    }

    private record ListenerBinding(AfterCommitEventListener<?> listener, Class<?> eventType) {

        boolean matches(Object event) {
            return eventType.isInstance(event);
        }

        @SuppressWarnings("unchecked")
        void deliver(Object event) {
            ((AfterCommitEventListener<Object>) listener).onEvent(event);
        }
    }
}
