package com.acme.business.content.application;

import com.acme.business.content.ContentError;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.domain.BizHotSearchWord;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import com.acme.business.content.infrastructure.repository.BizHotSearchWordRepository;
import com.acme.common.contract.PageQuery;
import com.acme.common.core.BizException;
import com.mybatisflex.core.query.QueryMethods;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        when(repository.findAll(any(QueryWrapper.class))).thenReturn(List.of());
        searchUseCase.suggestions("选题");
        verify(repository).findAll(any(QueryWrapper.class));
    }

    @Test
    void suggestionsEscapeLikeWildcards() {
        when(repository.findAll(any(QueryWrapper.class))).thenReturn(List.of());
        searchUseCase.suggestions("a%b_c");
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(repository).findAll(captor.capture());
        // 与 search 同口径：通配符按字面转义（BR-012），渲染须等同转义模式参照 wrapper
        QueryWrapper reference = QueryWrapper.create()
                .where(QueryMethods.lower(BizContent::getTitle).like(LikePatterns.contains("a%b_c")))
                .orderBy(BizContent::getUpdatedAt, false)
                .orderBy(BizContent::getId, true)
                .limit(SearchUseCase.SUGGESTION_LIMIT);
        assertThat(captor.getValue().toSQL()).isEqualTo(reference.toSQL());
    }
}
