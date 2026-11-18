package com.acme.common.contract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页协议基元测试（FR-023；本次重构定稿的缺省归一/上界/防御拷贝口径）。
 */
class PageTypesTest {

    @Test
    void queryNormalizesDefaults() {
        assertEquals(1, new PageQuery(null, null).page());
        assertEquals(PageQuery.DEFAULT_SIZE, new PageQuery(null, null).size());
        assertEquals(1, PageQuery.firstPage().page());
    }

    @Test
    void queryRejectsIllegalBoundsAsInvalidRequest() {
        // review 修复回归：越界抛 InvalidRequestException（framework-web 映射 HTTP 400），客户端误传不再落 500
        assertThrows(InvalidRequestException.class, () -> new PageQuery(0, 10));
        assertThrows(InvalidRequestException.class, () -> new PageQuery(1, 0));
        assertThrows(InvalidRequestException.class, () -> new PageQuery(1, PageQuery.MAX_SIZE + 1));
    }

    @Test
    void resultNormalizesNulls() {
        PageResult<String> result = new PageResult<>(null, null);
        assertTrue(result.records().isEmpty());
        assertEquals(0L, result.total());
        assertTrue(PageResult.empty().records().isEmpty());
    }

    @Test
    void resultCopiesDefensively() {
        List<String> source = new ArrayList<>(List.of("a"));
        PageResult<String> result = new PageResult<>(source, 1L);
        source.add("b");
        assertEquals(1, result.records().size());
        assertThrows(UnsupportedOperationException.class, () -> result.records().add("c"));
    }
}
