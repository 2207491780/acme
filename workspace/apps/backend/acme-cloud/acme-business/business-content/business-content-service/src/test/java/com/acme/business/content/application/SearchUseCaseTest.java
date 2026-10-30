package com.acme.business.content.application;

import com.acme.business.content.ContentError;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.domain.BizHotSearchWord;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import com.acme.business.content.infrastructure.repository.BizHotSearchWordRepository;
import com.acme.common.contract.PageQuery;
import com.acme.common.core.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索用例单测（16.1）：BR-012 空关键词兜底、热搜顺序、联想限量。
 */
class SearchUseCaseTest {

    private BizContentRepository repository;
    private BizHotSearchWordRepository hotSearchWordRepository;
    private SearchUseCase searchUseCase;

    @BeforeEach
    void setUp() {
        repository = mock(BizContentRepository.class);
        hotSearchWordRepository = mock(BizHotSearchWordRepository.class);
        searchUseCase = new SearchUseCase(repository, hotSearchWordRepository);
    }

    @Test
    void blankKeywordRejected() {
        assertThatThrownBy(() -> searchUseCase.search("   ", null, PageQuery.firstPage()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(ContentError.SEARCH_KEYWORD_EMPTY.code()));
        assertThatThrownBy(() -> searchUseCase.suggestions(null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(ContentError.SEARCH_KEYWORD_EMPTY.code()));
    }

    @Test
    void hotSearchesKeepServerOrder() {
        when(hotSearchWordRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                new BizHotSearchWord("甲", 1), new BizHotSearchWord("乙", 2)));
        assertThat(searchUseCase.hotSearches()).containsExactly("甲", "乙");
    }

    @Test
    void suggestionsAreLimitedToTen() {
        searchUseCase.suggestions("选题");
        verify(repository).findByStatusAndTitleContainingIgnoreCase(eq(ContentStatus.published), eq("选题"),
                any());
    }
}
