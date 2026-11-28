package com.acme.platform.workflow.kernel;

import com.acme.framework.stream.support.MessageSender;
import com.acme.platform.workflow.api.dto.WorkflowEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Kafka 事件流发布实现（REQ-20261122-233425 确认④；WorkflowEventPublisher SPI 缺省实现——
 * framework-stream 在场时经 WorkflowConfiguration 条件装配替换 Logging 为缺省）：
 * WorkflowEvent → {@link WorkflowEventEnvelope}（api 消费面类型化载荷）→ EventEnvelopes 信封
 * （messageId/幂等键/traceId 口径）→ topic {@code acme.workflow.events}（binding
 * workflowEventOut-out-0）。发布时序=AFTER_COMMIT 异步（EventPublishInterceptor 承载），
 * 回滚不发布；发送失败消化告警（SPI 口径：事件发布失败不反噬命令结果）。
 *
 * <p>发送线程：专属单线程 executor（broker 不可达时 binder 首发元数据拉取有 max.block.ms 级
 * 阻塞——隔离于公共池/事务线程，命令路径零影响；队列有界不设——事件量级=命令事件域）。</p>
 */
public class KafkaWorkflowEventPublisher implements WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaWorkflowEventPublisher.class);

    /** 出站绑定（SCSt 动态目标，destination=acme.workflow.events 归 application.yml）。 */
    public static final String BINDING = "workflowEventOut-out-0";

    /** 载荷协议版本（EventEnvelopes 信封 version 位）。 */
    static final String ENVELOPE_VERSION = "1.0";

    private final MessageSender messageSender;
    private final ScheduledExecutorService sendExecutor;

    public KafkaWorkflowEventPublisher(MessageSender messageSender) {
        this.messageSender = messageSender;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "acme-workflow-event-publisher");
            thread.setDaemon(true);
            return thread;
        });
        this.sendExecutor = executor;
    }

    @Override
    public void publish(WorkflowEvent event) {
        // 发送在专属线程执行（binder 首发元数据阻塞不反噬 afterCommit 线程/公共池；保序单线程）
        sendExecutor.execute(() -> doSend(event));
    }

    /** 分区键头名（REQ-20261128-183425 P2-12；workflow yml 绑定 producer
     *  messageKeyExpression=headers['partitionKey'] 消费——头名单源在此，binder-agnostic）。 */
    public static final String PARTITION_KEY_HEADER = "partitionKey";

    private void doSend(WorkflowEvent event) {
        try {
            WorkflowEventEnvelope envelope = new WorkflowEventEnvelope(event.eventType(),
                    event.instanceId(), event.definitionId(), event.definitionKey(), event.nodeId(),
                    event.businessKey(), event.tenantId(), event.traceId(), event.occurredAt());
            messageSender.send(BINDING, envelope, ENVELOPE_VERSION, partitionHeaders(event));
        } catch (RuntimeException ex) {
            // SPI 口径（实现须自行消化异常）：Kafka 不可达不反噬命令——告警留痕（审计面不受影响）
            log.warn("workflow 事件 Kafka 发布失败（不反噬命令）：type={} instance={} businessKey={}",
                    event.eventType(), event.instanceId(), event.businessKey(), ex);
        }
    }

    /** 分区键头（同实例事件同键同分区保序；instanceId 空不注入=无键路由，实例级锚点缺失属异常形态）。 */
    private Map<String, Object> partitionHeaders(WorkflowEvent event) {
        if (event.instanceId() == null) {
            return null;
        }
        return Map.of(PARTITION_KEY_HEADER, "instance-" + event.instanceId());
    }
}
