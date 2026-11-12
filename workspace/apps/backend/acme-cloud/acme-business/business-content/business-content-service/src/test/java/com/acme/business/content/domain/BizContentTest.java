package com.acme.business.content.domain;

import com.acme.business.content.ContentError;
import com.acme.business.content.api.ContentStatus;
import com.acme.common.core.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内容领域行为单测（16.1 核心逻辑）：rename（BR-003）、transitTo（BR-013）、forceDraft（QD-06）。
 */
class BizContentTest {

    private static BizContent content(ContentStatus status) {
        return new BizContent("原标题", "operation", "陈序", status, 10L, "摘要", "正文", null);
    }

    @Test
    void renameTrimsAndSaves() {
        BizContent content = content(ContentStatus.draft);
        content.rename("  新标题  ");
        assertThat(content.getTitle()).isEqualTo("新标题");
    }

    @Test
    void renameRejectsBlankTitle() {
        BizContent content = content(ContentStatus.draft);
        assertThatThrownBy(() -> content.rename("   "))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(ContentError.TITLE_EMPTY.code()));
        assertThatThrownBy(() -> content.rename(null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(ContentError.TITLE_EMPTY.code()));
    }

    @Test
    void transitToFollowsLegalMatrix() {
        BizContent content = content(ContentStatus.draft);
        content.transitTo(ContentStatus.reviewing);
        assertThat(content.getStatus()).isEqualTo(ContentStatus.reviewing);
        content.transitTo(ContentStatus.published);
        assertThat(content.getStatus()).isEqualTo(ContentStatus.published);
        content.transitTo(ContentStatus.draft);
        assertThat(content.getStatus()).isEqualTo(ContentStatus.draft);
    }

    @Test
    void transitToRejectsIllegalTransition() {
        BizContent content = content(ContentStatus.published);
        assertThatThrownBy(() -> content.transitTo(ContentStatus.reviewing))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo(ContentError.ILLEGAL_TRANSITION.code()));
    }

    @Test
    void forceDraftOverridesStateMachine() {
        // QD-06：批量下线为管理覆盖动作，无条件置 draft
        BizContent content = content(ContentStatus.published);
        content.forceDraft();
        assertThat(content.getStatus()).isEqualTo(ContentStatus.draft);
        content = content(ContentStatus.rejected);
        content.forceDraft();
        assertThat(content.getStatus()).isEqualTo(ContentStatus.draft);
    }
}
