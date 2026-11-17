package com.acme.platform.infra.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.common.context.TenantContext;
import com.acme.framework.excel.config.ExcelProperties;
import com.acme.framework.web.event.OpExceptionCapturedEvent;
import com.acme.platform.infra.InfraError;
import com.acme.platform.infra.domain.OpException;
import com.acme.platform.infra.infrastructure.redis.OpExceptionDedupStore;
import com.acme.platform.infra.infrastructure.repository.OpExceptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行异常日志用例单元测试（REQ-20261117-061017 确认①④；纯 JUnit + Mockito 无 Spring）：
 * 收集落库（租户绑定/列宽截断）、去重窗口跳过、Redis 不可达 fail-open、导出上限截断、详情缺失。
 */
class OpExceptionUseCaseTest {

    private OpExceptionRepository repository;
    private OpExceptionDedupStore dedupStore;
    private OpExceptionUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(OpExceptionRepository.class);
        dedupStore = mock(OpExceptionDedupStore.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        useCase = new OpExceptionUseCase(repository, dedupStore, transactionManager, new ExcelProperties());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 收集落库：事件 → 实体（列宽/堆栈截断），租户随事件 payload 绑定。 */
    @Test
    void should_persistEntityWithTruncatedFields_when_eventCollected() {
        when(dedupStore.tryAcquire(anyString(), anyString(), anyString())).thenReturn(true);

        useCase.collect(new OpExceptionCapturedEvent(
                "platform-demo", "trace-1", "span-1", "t-1", "op-1",
                "POST", "/demo/" + "x".repeat(300), 500,
                "java.lang.IllegalStateException", "m".repeat(1100), "S".repeat(600), Instant.now()));

        ArgumentCaptor<OpException> captor = ArgumentCaptor.forClass(OpException.class);
        verify(repository).save(captor.capture());
        OpException saved = captor.getValue();
        assertThat(saved.getPath()).hasSize(256);
        assertThat(saved.getMessage()).hasSize(1024);
        assertThat(saved.getStackSummary()).hasSize(512).as("收集侧堆栈摘要收口");
        assertThat(saved.getStatusCode()).isEqualTo(500);
        assertThat(saved.getService()).isEqualTo("platform-demo");
    }

    /** 去重窗口内重复（SETNX 失败）：跳过落库（确认① 60s 一次）。 */
    @Test
    void should_skipPersist_when_dedupKeyClaimed() {
        when(dedupStore.tryAcquire(anyString(), anyString(), anyString())).thenReturn(false);

        useCase.collect(new OpExceptionCapturedEvent(
                "platform-demo", "trace-2", null, "t-1", null,
                "GET", "/demo/x", 500, "java.lang.NullPointerException", "boom", "stack", Instant.now()));

        verify(repository, never()).save(any());
    }

    /** Redis 不可达 fail-open 在去重存储内收口（tryAcquire 恒返回）——用例层不再吞错；本用例验证调用面。 */
    @Test
    void should_callDedupStoreBeforePersist_when_eventCollected() {
        when(dedupStore.tryAcquire(anyString(), anyString(), anyString())).thenReturn(true);

        useCase.collect(new OpExceptionCapturedEvent(
                "platform-demo", "trace-3", null, "t-1", null,
                "GET", "/demo/x", 500, "java.lang.NullPointerException", "boom", "stack", Instant.now()));

        verify(dedupStore).tryAcquire("platform-demo", "java.lang.NullPointerException", "/demo/x");
        verify(repository).save(any(OpException.class));
    }

    /** 分页筛选：page/size 透传仓储（租户过滤随框架注入）。 */
    @Test
    void should_pageWithQueryParams_when_adminQueries() {
        OpException row = new OpException("platform-demo", "trace-1", null, "op-1",
                "GET", "/demo/x", 500, "java.lang.NullPointerException", "boom", "stack", Instant.now());
        when(repository.findAll(any(), eq(2), eq(50)))
                .thenReturn(new PageResult<>(List.of(row), 1L));

        PageResult<OpException> result =
                useCase.page(new PageQuery(2, 50), "platform-demo", null, null, null, null);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.records()).hasSize(1);
        verify(repository).findAll(any(), eq(2), eq(50));
    }

    /** 导出上限截断：取回超上限 → truncated 标记 + 截断到上限（确认④ 10000；注入小上限验证同径）。 */
    @Test
    void should_truncateExport_when_rowsExceedCap() {
        ExcelProperties properties = new ExcelProperties();
        properties.setMaxRows(2);
        useCase = new OpExceptionUseCase(repository, dedupStore,
                mock(PlatformTransactionManager.class), properties);
        List<OpException> rows = List.of(
                new OpException("s1", "t1", null, null, "GET", "/a", 500, "E", "m", "st", Instant.now()),
                new OpException("s2", "t2", null, null, "GET", "/b", 500, "E", "m", "st", Instant.now()),
                new OpException("s3", "t3", null, null, "GET", "/c", 500, "E", "m", "st", Instant.now()));
        when(repository.findAll(any(), eq(1), eq(3))).thenReturn(new PageResult<>(rows, 3L));

        ExportResult<OpException> result = useCase.export(null, null, null, null, null);

        assertThat(result.truncated()).as("3 行超上限 2 应截断").isTrue();
        assertThat(result.records()).hasSize(2);
    }

    /** 未超上限：原样返回，truncated=false。 */
    @Test
    void should_returnAllRows_when_rowsWithinCap() {
        List<OpException> rows = List.of(
                new OpException("s1", "t1", null, null, "GET", "/a", 500, "E", "m", "st", Instant.now()));
        when(repository.findAll(any(), eq(1), eq(10001))).thenReturn(new PageResult<>(rows, 1L));

        ExportResult<OpException> result = useCase.export(null, null, null, null, null);

        assertThat(result.truncated()).isFalse();
        assertThat(result.records()).hasSize(1);
    }

    /** 详情缺失：OP_EXCEPTION_NOT_FOUND（404）。 */
    @Test
    void should_throwOpExceptionNotFound_when_detailMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.get(99L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode()).isEqualTo(InfraError.OP_EXCEPTION_NOT_FOUND));
    }
}
