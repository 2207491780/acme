package com.acme.platform.infra.application;

import com.acme.framework.web.event.OpExceptionCapturedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 运行异常日志收集器（REQ-20261117-061017 确认①）：framework-web OpExceptionCapturedEvent 异步落库，
 * 对齐 LoginLogRecorder 异步口径（@Async 经代理执行，applicationTaskExecutor）——上下文已随事件
 * payload 走（发布侧捕获），异步线程不再持有 MDC/上下文。落库失败仅 WARN，不影响业务响应；
 * 非代理直构（单测）时同步执行，便于确定性断言。
 */
@Component
public class OpExceptionRecorder {

    private static final Logger log = LoggerFactory.getLogger(OpExceptionRecorder.class);

    private final OpExceptionUseCase opExceptionUseCase;

    public OpExceptionRecorder(OpExceptionUseCase opExceptionUseCase) {
        this.opExceptionUseCase = opExceptionUseCase;
    }

    /** 异步落库（任何失败仅 WARN，绝不外抛阻断响应主路径）。 */
    @Async
    @EventListener
    public void onCaptured(OpExceptionCapturedEvent event) {
        try {
            opExceptionUseCase.collect(event);
        } catch (RuntimeException ex) {
            log.warn("异常日志落库失败（不影响业务响应）：service={} type={}",
                    event == null ? null : event.service(),
                    event == null ? null : event.exceptionType(), ex);
        }
    }
}
