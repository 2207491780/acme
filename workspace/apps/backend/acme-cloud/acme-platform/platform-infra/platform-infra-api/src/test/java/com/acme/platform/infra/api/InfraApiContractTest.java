package com.acme.platform.infra.api;

import com.acme.platform.infra.api.dto.AdminAuditEventView;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-infra-api 契约测试（16.1，本地可执行口径）：DTO 形态稳定与错误码段位锁定。
 */
class InfraApiContractTest {

    @Test
    void auditEventViewShapeLocked() {
        Instant occurred = Instant.parse("2026-10-26T08:30:00Z");
        AdminAuditEventView view = new AdminAuditEventView(
                "1234567890123456789", "evt-1", "trace-1", "span-1", "default",
                "42", "B", "infra.audit.list", "auditEvent", null,
                "查询审计", occurred, occurred);
        assertThat(view.id()).isEqualTo("1234567890123456789");
        assertThat(view.eventId()).isEqualTo("evt-1");
        assertThat(view.action()).isEqualTo("infra.audit.list");
        assertThat(view.occurredAt()).isEqualTo(occurred);
    }

    @Test
    void errorCodesLayoutLocked() {
        // 三段式结构（ErrorCodeLayout 分配规则：serviceId=platform-infra，模块段 2 位）
        assertThat(InfraErrorCodes.AUDIT_EVENT_INVALID).isEqualTo("platform-infra-01-0001");
        assertThat(InfraErrorCodes.AUDIT_STORE_FAILED).isEqualTo("platform-infra-01-0002");
    }
}
