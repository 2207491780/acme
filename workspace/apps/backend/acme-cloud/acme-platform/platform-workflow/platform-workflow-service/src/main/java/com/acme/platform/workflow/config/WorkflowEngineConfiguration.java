package com.acme.platform.workflow.config;

import com.acme.platform.workflow.application.WorkflowInternalUseCase;
import com.acme.platform.workflow.engine.TimerScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 引擎装配：定时桥（TimerScheduler 兜底/重试面 → 命令内核的唯一桥接）。
 * ObjectProvider 惰性解析打断构造环（internalUseCase → 命令 → 引擎 → TimerScheduler → 桥 →
 * internalUseCase）；桥方法在兜底线程运行（上下文就绪后必可达）。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowEngineConfiguration {

    /** 定时桥装配（fire → TimerFireCommand；ServiceTask 重试 → ServiceTaskRetryCommand）。 */
    @Bean
    public TimerScheduler.WorkflowPropertiesBridge workflowTimerBridge(
            ObjectProvider<WorkflowInternalUseCase> internalUseCases, WorkflowProperties properties) {
        return new TimerScheduler.WorkflowPropertiesBridge() {

            @Override
            public void fireTimer(Long timerJobId, String schedulerExecutionId) {
                internalUseCases.getObject().fireTimerQuietly(timerJobId);
            }

            @Override
            public void dispatchServiceTaskRetry(Long executionId, int attempt) {
                internalUseCases.getObject().serviceTaskRetry(executionId, attempt);
            }

            @Override
            public WorkflowProperties.Timer timer() {
                return properties.getTimer();
            }
        };
    }
}
