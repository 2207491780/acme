package com.acme.platform.infra.api;

import com.acme.platform.infra.api.dto.AdminAuditEventView;
import com.acme.platform.infra.api.dto.FileView;
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
    void fileViewShapeLocked() {
        // REQ-20261114-071607 §API FileView：id/originalName/size/contentType/url/bizType/bizId/createdAt
        Instant createdAt = Instant.parse("2026-11-14T08:30:00Z");
        FileView view = new FileView("1234567890123456789", "hello.xlsx", 1024L,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "http://files.localhost/acme/files/2026/11/14/x.xlsx", "req", "42", createdAt);
        assertThat(view.id()).isEqualTo("1234567890123456789");
        assertThat(view.originalName()).isEqualTo("hello.xlsx");
        assertThat(view.size()).isEqualTo(1024L);
        assertThat(view.url()).startsWith("http://files.localhost/acme");
        assertThat(view.bizType()).isEqualTo("req");
        assertThat(view.bizId()).isEqualTo("42");
        assertThat(view.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void errorCodesLayoutLocked() {
        // 三段式结构（ErrorCodeLayout 分配规则：serviceId=platform-infra，模块段 2 位）
        assertThat(InfraErrorCodes.AUDIT_EVENT_INVALID).isEqualTo("platform-infra-01-0001");
        assertThat(InfraErrorCodes.AUDIT_STORE_FAILED).isEqualTo("platform-infra-01-0002");
        // REQ-20261114-071607 六码：契约 §API「错误码在此列全」符号字面量（Result.code 口径）
        assertThat(InfraErrorCodes.FILE_SIZE_EXCEEDED).isEqualTo("FILE_SIZE_EXCEEDED");
        assertThat(InfraErrorCodes.FILE_TYPE_REJECTED).isEqualTo("FILE_TYPE_REJECTED");
        assertThat(InfraErrorCodes.FILE_NOT_FOUND).isEqualTo("FILE_NOT_FOUND");
        assertThat(InfraErrorCodes.EXCEL_PARSE_FAILED).isEqualTo("EXCEL_PARSE_FAILED");
        assertThat(InfraErrorCodes.JOB_ALREADY_RUNNING).isEqualTo("JOB_ALREADY_RUNNING");
        assertThat(InfraErrorCodes.JOB_NOT_FOUND).isEqualTo("JOB_NOT_FOUND");
    }
}
