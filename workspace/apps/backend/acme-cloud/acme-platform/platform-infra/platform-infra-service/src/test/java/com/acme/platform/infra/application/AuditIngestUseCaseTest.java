package com.acme.platform.infra.application;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.audit.support.AuditEvent;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.infrastructure.repository.OpAuditEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计落库用例测试：必填校验（毒消息显式失败）、重复投递幂等跳过（M-12 双保险：
 * exists 快路径 + 唯一约束兜底）、租户归一与上下文恢复。
 */
class AuditIngestUseCaseTest {

    private final OpAuditEventRepository repository = mock(OpAuditEventRepository.class);
    // 事务模板走 mock 事务管理器：单测只验证编排语义，真实事务边界由集成测试覆盖
    private final AuditIngestUseCase useCase =
            new AuditIngestUseCase(repository, mock(PlatformTransactionManager.class));

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void invalidEventFailsExplicitly() {
        assertThatThrownBy(() -> useCase.ingest(new AuditEvent(null, "t", "s", "default",
                "42", "B", "order.create", "order", "1", "x", Instant.now(), "unit")))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(InfraError.AUDIT_EVENT_INVALID.code()));
        assertThatThrownBy(() -> useCase.ingest(new AuditEvent("evt-1", "t", "s", "default",
                "42", "B", " ", "order", "1", "x", Instant.now(), "unit")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> useCase.ingest(new AuditEvent("evt-1", "t", "s", "default",
                "42", "B", "order.create", "order", "1", "x", null, "unit")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void duplicateEventSkipsByIdempotency() {
        when(repository.existsByEventId("evt-1")).thenReturn(true);
        useCase.ingest(event("evt-1", "tenant-a"));
        verify(repository, never()).saveAndFlush(any(OpAuditEvent.class));
    }

    @Test
    void uniqueViolationBackstopSkipsSilently() {
        when(repository.existsByEventId("evt-2")).thenReturn(false);
        when(repository.saveAndFlush(any(OpAuditEvent.class)))
                .thenThrow(new DataIntegrityViolationException("uk_infra_audit_event"));
        useCase.ingest(event("evt-2", "tenant-a"));
        verify(repository).saveAndFlush(any(OpAuditEvent.class));
    }

    @Test
    void savesEventFieldsAndRestoresTenantContext() {
        when(repository.existsByEventId(anyString())).thenReturn(false);
        TenantContext.bind(new TenantContext("caller-tenant"));
        useCase.ingest(event("evt-3", "tenant-a"));

        ArgumentCaptor<OpAuditEvent> captor = ArgumentCaptor.forClass(OpAuditEvent.class);
        verify(repository).saveAndFlush(captor.capture());
        OpAuditEvent saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo("evt-3");
        assertThat(saved.getAction()).isEqualTo("order.create");
        assertThat(saved.getOperatorId()).isEqualTo("42");
        // 落库作用域结束后恢复调用方上下文
        assertThat(TenantContext.get()).isNotNull();
        assertThat(TenantContext.get().tenantId()).isEqualTo("caller-tenant");
    }

    @Test
    void blankTenantNormalizesToSystem() {
        // 租户归一（系统级事件）：bind 作用域以归一租户驱动 @TenantId 注入
        when(repository.existsByEventId(anyString())).thenReturn(false);
        useCase.ingest(event("evt-4", null));
        // 无异常且恢复正常（注入效果由集成测试落库断言覆盖）
        assertThat(TenantContext.get()).isNull();
    }

    private static AuditEvent event(String eventId, String tenantId) {
        return new AuditEvent(eventId, "trace-1", "span-1", tenantId,
                "42", "B", "order.create", "order", "1", "下单", Instant.now(), "unit-test");
    }
}
