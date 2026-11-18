package com.acme.platform.infra.application;

import com.acme.framework.flex.support.AuditBase;
import com.acme.platform.infra.config.AuditRetentionProperties;
import com.acme.platform.infra.config.ExceptionLogRetentionProperties;
import com.acme.platform.infra.domain.OpAuditEvent;
import com.acme.platform.infra.domain.OpException;
import com.acme.platform.infra.infrastructure.repository.OpAuditEventRepository;
import com.acme.platform.infra.infrastructure.repository.OpExceptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保留期清理用例单元测试（REQ-20261117-061017 确认②，AC-4 用例面；纯 JUnit + Mockito）：
 * 先查后删（软删超期硬删、未超期保留、空集零删除）。端到端由 PG 集成测试覆盖。
 */
class RetentionCleanupUseCaseTest {

    private OpExceptionRepository opExceptionRepository;
    private OpAuditEventRepository opAuditEventRepository;
    private OpExceptionRetentionCleanupUseCase exceptionCleanup;
    private AuditRetentionCleanupUseCase auditCleanup;

    @BeforeEach
    void setUp() {
        opExceptionRepository = mock(OpExceptionRepository.class);
        opAuditEventRepository = mock(OpAuditEventRepository.class);
        exceptionCleanup = new OpExceptionRetentionCleanupUseCase(opExceptionRepository,
                new ExceptionLogRetentionProperties());
        auditCleanup = new AuditRetentionCleanupUseCase(opAuditEventRepository,
                new AuditRetentionProperties());
    }

    /** 异常日志清理：软删超期行按 id 集硬删，返回清理条数（确认②）。 */
    @Test
    void should_forceDeleteExpiredRows_when_exceptionRetentionCleanup() {
        OpException expired = exceptionRow(910301L);
        when(opExceptionRepository.findSoftDeletedExpiredBefore(any(Instant.class)))
                .thenReturn(List.of(expired));

        int removed = exceptionCleanup.cleanupExpired();

        assertThat(removed).isEqualTo(1);
        verify(opExceptionRepository).forceDeleteByIds(argThat(ids -> ids.contains(910301L)));
    }

    /** 无超期行：零删除（不落物理删除旁路）。 */
    @Test
    void should_deleteNothing_when_noExpiredRows() {
        when(opExceptionRepository.findSoftDeletedExpiredBefore(any(Instant.class)))
                .thenReturn(List.of());

        assertThat(exceptionCleanup.cleanupExpired()).isZero();
        verify(opExceptionRepository, never()).forceDeleteByIds(any());
    }

    /** 审计清理：软删超期行按 id 集硬删（确认②）。 */
    @Test
    void should_forceDeleteExpiredRows_when_auditRetentionCleanup() {
        OpAuditEvent expired = auditRow(910401L);
        when(opAuditEventRepository.findSoftDeletedExpiredBefore(any(Instant.class)))
                .thenReturn(List.of(expired));

        int removed = auditCleanup.cleanupExpired();

        assertThat(removed).isEqualTo(1);
        verify(opAuditEventRepository).forceDeleteByIds(argThat(ids -> ids.contains(910401L)));
    }

    /** 保留期阈值取配置缺省（异常 14 / 审计 30，确认②）。 */
    @Test
    void should_useConfiguredRetentionDays() {
        assertThat(new ExceptionLogRetentionProperties().getRetentionDays()).isEqualTo(14);
        assertThat(new ExceptionLogRetentionProperties().getRetentionCron()).isEqualTo("0 40 3 * * *");
        assertThat(new AuditRetentionProperties().getRetentionDays()).isEqualTo(30);
        assertThat(new AuditRetentionProperties().getRetentionCron()).isEqualTo("0 40 4 * * *");
    }

    private static OpException exceptionRow(long id) {
        OpException row = new OpException("platform-demo", "trace-x", null, null,
                "GET", "/demo/x", 500, "java.lang.IllegalStateException", "m", "stack", Instant.now());
        return withId(row, id);
    }

    private static OpAuditEvent auditRow(long id) {
        OpAuditEvent row = new OpAuditEvent("evt-x", "trace-x", null, "op-1", "B",
                "demo.action", null, null, "content", Instant.now());
        return withId(row, id);
    }

    private static <T extends AuditBase> T withId(T entity, long id) {
        try {
            Field idField = AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return entity;
    }
}
