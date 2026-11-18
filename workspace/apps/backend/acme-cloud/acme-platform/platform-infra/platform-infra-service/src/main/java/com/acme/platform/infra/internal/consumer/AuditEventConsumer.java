package com.acme.platform.infra.internal.consumer;

import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.stream.support.EventEnvelopes;
import com.acme.platform.infra.application.AuditIngestUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 审计事件消费函数（binding=auditEventIn-in-0，主题 infra-audit-events，消费组 platform-infra）：
 * 消费管线（messageId 去重门 M-12 + 上下文恢复）由 framework-stream 入站通道接线；
 * 解析失败/落库失败抛出 → SCSt 重试 → 死信 infra-audit-events-dlq（13.2.2）。
 */
@Configuration
public class AuditEventConsumer {

    @Bean
    public Consumer<Message<String>> auditEventIn(AuditIngestUseCase ingestUseCase, JsonMapper jsonMapper) {
        return message -> {
            String payload = payloadText(message.getPayload());
            AuditEvent event = EventEnvelopes.parse(payload, AuditEvent.class, jsonMapper).getPayload();
            ingestUseCase.ingest(event);
        };
    }

    /** SCSt 函数调用面载荷二态兼容（String / byte[]，p4 先例）。 */
    private static String payloadText(Object payload) {
        if (payload instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(payload);
    }
}
