package com.acme.platform.infra.internal.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.framework.tenant.support.CrossTenantAuditEvent;
import com.acme.platform.infra.domain.CrossTenantAuditRow;
import com.acme.platform.infra.infrastructure.repository.CrossTenantAuditRowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 跨租户审计 DB sink 测试（REQ-20261125-001736 AC-3）：事件→行映射、写失败 ERROR 告警并丢弃该行
 * （主流程零影响、不抛出）、空事件忽略、入队被拒降级告警。
 */
class CrossTenantAuditDbSinkTest {

    private final CrossTenantAuditRowRepository repository = mock(CrossTenantAuditRowRepository.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void cleanup() {
        Logger logger = (Logger) LoggerFactory.getLogger(CrossTenantAuditDbSink.class);
        logger.detachAppender(appender);
    }

    private void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(CrossTenantAuditDbSink.class);
        appender.list.clear();
        appender.start();
        logger.addAppender(appender);
    }

    private static CrossTenantAuditEvent event() {
        return new CrossTenantAuditEvent("admin-1", "*", "AdminPlatform#listAll",
                "trace-1", CrossTenantAuditEvent.BYPASS_ROOT, null);
    }

    @Test
    void should_persistMappedRow_when_eventAccepted() {
        attachAppender();
        CrossTenantAuditDbSink sink = new CrossTenantAuditDbSink(repository, directExecutor(), false);

        sink.accept(event());

        ArgumentCaptor<CrossTenantAuditRow> captor = ArgumentCaptor.forClass(CrossTenantAuditRow.class);
        verify(repository).save(captor.capture());
        CrossTenantAuditRow row = captor.getValue();
        assertThat(row.getOperator()).isEqualTo("admin-1");
        assertThat(row.getTenantId()).isEqualTo("*");
        assertThat(row.getAccessPoint()).isEqualTo("AdminPlatform#listAll");
        assertThat(row.getTraceId()).isEqualTo("trace-1");
        assertThat(row.getBypass()).isEqualTo(CrossTenantAuditEvent.BYPASS_ROOT);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void should_logErrorAndDropRow_when_repositoryFails() {
        attachAppender();
        doThrow(new IllegalStateException("db down")).when(repository).save(any());
        CrossTenantAuditDbSink sink = new CrossTenantAuditDbSink(repository, directExecutor(), false);

        // 写失败：ERROR 告警并丢弃该行，不向调用方抛出（主流程零影响，AC-3）
        assertThatCode(() -> sink.accept(event())).doesNotThrowAnyException();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("ERROR");
        assertThat(appender.list.get(0).getFormattedMessage()).contains("落库失败");
    }

    @Test
    void should_ignoreNullEvent() {
        attachAppender();
        CrossTenantAuditDbSink sink = new CrossTenantAuditDbSink(repository, directExecutor(), false);

        sink.accept(null);

        verify(repository, never()).save(any());
    }

    @Test
    void should_logErrorAndNotThrow_when_submissionRejected() {
        attachAppender();
        ExecutorService rejectingExecutor = new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("queue full");
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }
        };
        CrossTenantAuditDbSink sink = new CrossTenantAuditDbSink(repository, rejectingExecutor, false);

        assertThatCode(() -> sink.accept(event())).doesNotThrowAnyException();

        verify(repository, never()).save(any());
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("ERROR");
    }

    /** 直接执行器（同步落库，便于断言）。 */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }
        };
    }
}
