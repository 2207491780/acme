package com.acme.bootstrap.audit;

import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.audit.support.AuditEventSink;
import com.acme.platform.infra.application.AuditIngestUseCase;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 单体本地审计 sink（REQ-20261118-002130 确认③）：单体本地短路；分布式由 MQ 提供
 * （{@code AuditEventMqSink} → infra-audit-events 主题 → AuditEventConsumer 消费回环）。
 * 同 JVM 去掉 MQ 回环——@Primary 直调 {@link AuditIngestUseCase#ingest}（去重门 M-12 语义
 * 保留：event_id 唯一约束幂等在用例内不受通道影响）；流关闭形态下 MQ 桥接 bean 按既有降级
 * 口径退化为空操作（MessageSender 缺席），不参与落库。
 * 失败语义：ingest 校验失败显式抛出（毒事件死信口径），由
 * {@code SpringAuditEventPublisher} 兜底 ERROR（不影响业务主流程，BR-013）。
 */
@Component
@Primary
public class LocalAuditEventSink implements AuditEventSink {

    private final AuditIngestUseCase auditIngestUseCase;

    public LocalAuditEventSink(AuditIngestUseCase auditIngestUseCase) {
        this.auditIngestUseCase = auditIngestUseCase;
    }

    /** 接收审计事件：本地直落 acme_op_audit（无 Kafka 出站）。 */
    @Override
    public void onEvent(AuditEvent event) {
        auditIngestUseCase.ingest(event);
    }
}
