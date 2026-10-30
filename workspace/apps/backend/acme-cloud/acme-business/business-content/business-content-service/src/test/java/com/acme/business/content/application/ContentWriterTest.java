package com.acme.business.content.application;

import com.acme.business.content.ContentError;
import com.acme.business.content.api.BatchAction;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.api.dto.AdminContentBatchResultView;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容写入器单测（16.1 核心逻辑）：改题并发（QD-04）、状态机幂等（10.3.1）、
 * 批量逐条口径（架构设计 6.2：从未存在 / 已删 no-op / 存在执行）。
 */
class ContentWriterTest {

    private BizContentRepository repository;
    private ContentWriter contentWriter;

    @BeforeEach
    void setUp() {
        repository = mock(BizContentRepository.class);
        contentWriter = new ContentWriter(repository);
    }

    private static BizContent row(Long id, ContentStatus status) {
        BizContent content = new BizContent("标题", "operation", "陈序", status, 0L, "摘要", "正文", null);
        // 模拟持久化行：id / version 经反射注入（测试构造不走 @PrePersist）
        try {
            var idField = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(content, id);
            var versionField = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("version");
            versionField.setAccessible(true);
            versionField.set(content, 0L);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return content;
    }

    @Test
    void renameRejectsVersionConflict() {
        BizContent content = row(1L, ContentStatus.draft);
        when(repository.findById(1L)).thenReturn(Optional.of(content));
        assertThatThrownBy(() -> contentWriter.rename(1L, "新标题", 5L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(WebError.CONCURRENCY_CONFLICT.code()));
    }

    @Test
    void renameAppliesWhenVersionMatchesOrAbsent() {
        BizContent content = row(1L, ContentStatus.draft);
        when(repository.findById(1L)).thenReturn(Optional.of(content));
        when(repository.save(content)).thenReturn(content);
        contentWriter.rename(1L, "新标题", 0L);
        assertThat(content.getTitle()).isEqualTo("新标题");
        contentWriter.rename(1L, "再改", null);
        assertThat(content.getTitle()).isEqualTo("再改");
    }

    @Test
    void renameMissingContentFails404() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> contentWriter.rename(9L, "新标题", null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(ContentError.CONTENT_NOT_FOUND.code()));
    }

    @Test
    void sameStateTransitionIsIdempotent() {
        BizContent content = row(1L, ContentStatus.published);
        when(repository.findById(1L)).thenReturn(Optional.of(content));
        BizContent result = contentWriter.transition(1L, ContentStatus.published);
        assertThat(result.getStatus()).isEqualTo(ContentStatus.published);
        verify(repository, never()).save(content);
    }

    @Test
    void batchClassifiesNeverExistedDeletedAndLive() {
        // id=1 存在未删（published）；id=2 已删；id=999 从未存在；"bad" 解析失败
        BizContent live = row(1L, ContentStatus.published);
        when(repository.findRowStatesIncludingDeleted(anyCollection())).thenReturn(List.of(
                rowState(1L, false), rowState(2L, true)));
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(live));

        AdminContentBatchResultView result = contentWriter.batch(BatchAction.offline, List.of("1", "2", "999", "bad"));

        assertThat(result.total()).isEqualTo(4L);
        // 已删 2 幂等成功 no-op；存活 1 执行下线成功
        assertThat(result.succeededIds()).containsExactly("2", "1");
        assertThat(live.getStatus()).isEqualTo(ContentStatus.draft);
        assertThat(result.failures()).hasSize(2);
        assertThat(result.failures()).extracting(f -> f.id()).containsExactly("bad", "999");
        assertThat(result.failures()).allSatisfy(f ->
                assertThat(f.code()).isEqualTo("business-content-01-0001"));
    }

    @Test
    void batchDeleteIsSoftAndIdempotent() {
        BizContent live = row(1L, ContentStatus.published);
        when(repository.findRowStatesIncludingDeleted(anyCollection())).thenReturn(List.of(rowState(1L, false)));
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(live));

        AdminContentBatchResultView result = contentWriter.batch(BatchAction.delete, List.of("1"));

        assertThat(result.succeededIds()).containsExactly("1");
        verify(repository).delete(live);
    }

    @Test
    void batchDeduplicatesIds() {
        when(repository.findRowStatesIncludingDeleted(anyCollection())).thenReturn(List.of(rowState(1L, true)));
        AdminContentBatchResultView result = contentWriter.batch(BatchAction.delete, List.of("1", " 1 ", "01"));
        // "01" 与 "1" 解析为同一 id：去重后 total=1
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.succeededIds()).containsExactly("1");
    }

    private static BizContentRepository.ContentRowState rowState(Long id, boolean deleted) {
        return new BizContentRepository.ContentRowState() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Boolean getDeleted() {
                return deleted;
            }
        };
    }
}
