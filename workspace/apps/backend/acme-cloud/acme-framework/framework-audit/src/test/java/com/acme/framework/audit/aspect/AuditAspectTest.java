package com.acme.framework.audit.aspect;

import com.acme.common.context.ClientContext;
import com.acme.common.context.TenantContext;
import com.acme.common.context.TraceContext;
import com.acme.common.context.UserContext;
import com.acme.framework.audit.config.AuditProperties;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.framework.audit.support.AuditEventPublisher;
import com.acme.framework.audit.support.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计埋点测试（FR-008/009/010、AC-008/009/010：上下文关联、未声明不记录、失败不阻断业务）。
 */
class AuditAspectTest {

    private final List<AuditEvent> events = new ArrayList<>();
    private final AuditEventPublisher publisher = events::add;

    @AfterEach
    void cleanup() {
        UserContext.clear();
        TenantContext.clear();
        ClientContext.clear();
        TraceContext.clear();
    }

    private Sample proxy() {
        AuditAspect aspect = new AuditAspect(publisher, new AuditProperties());
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleImpl());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    void annotatedMethodProducesEventWithFullContext() {
        UserContext.bind(new UserContext("op-1", Set.of()));
        TenantContext.bind(new TenantContext("t-1"));
        ClientContext.bind(new ClientContext(com.acme.common.context.Channel.B, "dev-1", "1.0"));
        TraceContext.bind(new TraceContext("trace-1", "span-1"));

        assertThat(proxy().audited("target-1", "hunter2")).isEqualTo("done");
        assertThat(events).hasSize(1);
        AuditEvent event = events.getFirst();
        assertThat(event.action()).isEqualTo("demo.action");
        assertThat(event.targetType()).isEqualTo("Demo");
        assertThat(event.targetId()).isEqualTo("target-1");
        assertThat(event.operatorId()).isEqualTo("op-1");
        assertThat(event.tenantId()).isEqualTo("t-1");
        assertThat(event.channel()).isEqualTo("B");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.content()).contains("password=***").doesNotContain("hunter2");
    }

    @Test
    void unannotatedMethodProducesNoEvent() {
        proxy().plain();
        assertThat(events).isEmpty();
    }

    @Test
    void publisherFailureDoesNotBlockBusiness() {
        AuditAspect aspect = new AuditAspect(event -> {
            throw new IllegalStateException("publish failed");
        }, new AuditProperties());
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleImpl());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        Sample proxy = factory.getProxy();
        assertThat(proxy.audited("t", "hunter2")).isEqualTo("done");
    }

    interface Sample {
        String audited(String id, String password);

        String plain();
    }

    static class SampleImpl implements Sample {
        @Override
        @AuditLog(action = "demo.action", targetType = "'Demo'", targetId = "#p0", contentParams = {"password"})
        public String audited(String id, String password) {
            return "done";
        }

        @Override
        public String plain() {
            return "plain";
        }
    }
}
