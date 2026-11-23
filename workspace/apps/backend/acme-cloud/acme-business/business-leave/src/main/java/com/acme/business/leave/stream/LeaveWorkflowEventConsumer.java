package com.acme.business.leave.stream;

import com.acme.business.leave.application.LeaveRequestUseCase;
import com.acme.framework.stream.support.EventEnvelopes;
import com.acme.platform.workflow.api.WorkflowEventTypes;
import com.acme.platform.workflow.api.dto.WorkflowEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 流程事件自消费（REQ-20261122-233425 确认⑤⑤——事件流第一个真实业务消费先例；通知中心消费
 * 接线归 BPM-6）：binding=leaveEventIn-in-0，topic=acme.workflow.events（发布方约定）。
 * 状态机驱动面（确认⑤状态链序）：TASK_COMPLETED→APPROVING（审批办结、HR 备案前）、
 * TASK_REJECTED→REJECTED（审批驳回终态）、INSTANCE_COMPLETED→DONE/REJECTED 终态回写
 * （备案链走通=实例变量 filed=true，else 未走备案链）。
 *
 * <p>装配口径：随 framework-stream 开关启停（{@code acme.stream.enabled}，单体 application.yml
 * 显式 false——无 MQ 回环角色，同 auditEventIn 消费面语义；集成测试/独立形态开启即生效）。
 * 消费管线（messageId 去重门 + 上下文恢复）由 framework-stream 入站通道接线；解析失败抛出 →
 * SCSt 重试 → 死信 leaveEventIn-in-0（消费方部署面配置）。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "acme.stream", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class LeaveWorkflowEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LeaveWorkflowEventConsumer.class);

    /** 消费函数（SCSt 函数绑定；bean 名=函数定义名 leaveEventIn）。 */
    @Bean
    public Consumer<Message<String>> leaveEventIn(LeaveRequestUseCase leaveRequestUseCase,
                                                  JsonMapper jsonMapper) {
        return message -> {
            String payload = payloadText(message.getPayload());
            WorkflowEventEnvelope event = EventEnvelopes.parse(payload, WorkflowEventEnvelope.class,
                    jsonMapper).getPayload();
            // 消费线程上下文回放（发布侧 AFTER_COMMIT 异步线程头上下文缺省——租户自载荷显式恢复，
            // 租户过滤面依赖；BR-025 请求结束清理语义由 finally 承接）
            com.acme.common.context.TenantContext.bind(
                    new com.acme.common.context.TenantContext(event.tenantId()));
            try {
                switch (event.eventType() == null ? "" : event.eventType()) {
                    case WorkflowEventTypes.TASK_COMPLETED ->
                            leaveRequestUseCase.onTaskCompleted(event.instanceId(), event.businessKey());
                    case WorkflowEventTypes.TASK_REJECTED ->
                            leaveRequestUseCase.onTaskRejected(event.instanceId(), event.businessKey());
                    case WorkflowEventTypes.INSTANCE_COMPLETED ->
                            leaveRequestUseCase.onInstanceCompleted(event.instanceId(), event.businessKey());
                    default -> log.debug("流程事件非请假状态域跳过：type={} businessKey={}",
                            event.eventType(), event.businessKey());
                }
            } finally {
                com.acme.common.context.TenantContext.clear();
            }
        };
    }

    /** SCSt 函数调用面载荷二态兼容（String / byte[]，AuditEventConsumer 同款）。 */
    private static String payloadText(Object payload) {
        if (payload instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(payload);
    }
}
