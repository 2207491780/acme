package com.acme.business.content;

import com.acme.business.content.api.ContentErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码双源一致性测试（架构设计 4.2：ContentError 枚举与 ContentErrorCodes 常量一一对应）。
 */
class ContentErrorCodesMatchTest {

    @Test
    void enumCodesMatchApiConstants() {
        assertThat(ContentError.CONTENT_NOT_FOUND.code()).isEqualTo(ContentErrorCodes.CONTENT_NOT_FOUND);
        assertThat(ContentError.TITLE_EMPTY.code()).isEqualTo(ContentErrorCodes.TITLE_EMPTY);
        assertThat(ContentError.ILLEGAL_TRANSITION.code()).isEqualTo(ContentErrorCodes.ILLEGAL_TRANSITION);
        assertThat(ContentError.SEARCH_KEYWORD_EMPTY.code()).isEqualTo(ContentErrorCodes.SEARCH_KEYWORD_EMPTY);
        assertThat(ContentError.FEED_TAB_UNSUPPORTED.code()).isEqualTo(ContentErrorCodes.FEED_TAB_UNSUPPORTED);
    }

    @Test
    void httpStatusesLocked() {
        // 架构设计 4.2 表：404 / 400 / 409 / 400 / 400
        assertThat(ContentError.CONTENT_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ContentError.TITLE_EMPTY.httpStatus()).isEqualTo(400);
        assertThat(ContentError.ILLEGAL_TRANSITION.httpStatus()).isEqualTo(409);
        assertThat(ContentError.SEARCH_KEYWORD_EMPTY.httpStatus()).isEqualTo(400);
        assertThat(ContentError.FEED_TAB_UNSUPPORTED.httpStatus()).isEqualTo(400);
    }
}
