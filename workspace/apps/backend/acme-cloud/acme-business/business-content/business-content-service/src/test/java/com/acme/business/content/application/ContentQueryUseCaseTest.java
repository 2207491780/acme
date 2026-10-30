package com.acme.business.content.application;

import com.acme.business.content.ContentError;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import com.acme.common.contract.PageQuery;
import com.acme.common.core.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容查询用例单测（16.1）：feed tab 语义（QD-02 / FR-001 未知值空集不报错）、详情 published 过滤（BR-002）。
 */
class ContentQueryUseCaseTest {

    private BizContentRepository repository;
    private ContentQueryUseCase contentQueryUseCase;

    @BeforeEach
    void setUp() {
        repository = mock(BizContentRepository.class);
        contentQueryUseCase = new ContentQueryUseCase(repository);
    }

    @Test
    void feedUnknownTabReturnsEmptyWithoutQuery() {
        assertThat(contentQueryUseCase.feed("unknown")).isEmpty();
        assertThat(contentQueryUseCase.feed(null)).isEmpty();
        assertThat(contentQueryUseCase.feed("follow")).isEmpty();
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    void feedRecommendQueriesPublished() {
        when(repository.findByStatus(eq(ContentStatus.published), any())).thenReturn(List.of());
        assertThat(contentQueryUseCase.feed("recommend")).isEmpty();
        verify(repository).findByStatus(eq(ContentStatus.published), any());
    }

    @Test
    void detailFiltersUnpublished() {
        BizContent draft = new BizContent("标题", "operation", "陈序", ContentStatus.draft, 0L, "摘要", "正文", null);
        when(repository.findById(1L)).thenReturn(Optional.of(draft));
        assertThat(contentQueryUseCase.detail(1L)).isEmpty();
        assertThat(contentQueryUseCase.detail(2L)).isEmpty();
    }

    @Test
    void libraryPassesSortAndPage() {
        when(repository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        var result = contentQueryUseCase.library("operation", com.acme.business.content.api.LibrarySort.hottest,
                new PageQuery(1, 20));
        assertThat(result.total()).isZero();
    }

    @Test
    void adminPageRejectsNothingOnBlankFilters() {
        when(repository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        var result = contentQueryUseCase.adminPage(null, null, null, null,
                com.acme.business.content.api.AdminSortField.updatedAt, false, new PageQuery(1, 20));
        assertThat(result.records()).isEmpty();
    }
}
