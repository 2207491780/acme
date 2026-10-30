package com.acme.platform.infra.internal.mq;

import com.acme.framework.stream.support.MessageSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审计事件桥接装配：MessageSender 缺席（acme.stream.enabled=false）时桥接降级空操作，
 * 保持 audit 模块自身开关独立性（Modules.md 3.5 可关闭性）。
 */
@Configuration
public class AuditMqBridgeConfig {

    @Bean
    public AuditEventMqSink auditEventMqSink(ObjectProvider<MessageSender> sender) {
        return new AuditEventMqSink(sender.getIfAvailable());
    }
}
