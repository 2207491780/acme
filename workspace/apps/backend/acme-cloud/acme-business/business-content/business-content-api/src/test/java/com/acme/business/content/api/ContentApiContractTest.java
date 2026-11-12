package com.acme.business.content.api;

import com.acme.business.content.api.dto.AdminBatchFailureView;
import com.acme.business.content.api.dto.AdminContentBatchResultView;
import com.acme.business.content.api.dto.AdminContentRowView;
import com.acme.business.content.api.dto.AdminContentTitleUpdate;
import com.acme.business.content.api.dto.AppContentItemView;
import com.acme.common.contract.ErrorCodeLayout;
import com.acme.common.contract.PageResult;
import com.acme.common.serialization.JsonModules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * business-content-api 契约测试（16.1）：错误码布局 / wire 枚举名 / 状态机集合 / 权限点 /
 * DTO 形态与序列化 wire 形态（Long→String、枚举名、时间 ISO-8601）锁定。
 */
class ContentApiContractTest {

    private final JsonMapper mapper = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        return builder.build();
    }

    @Test
    void errorCodesLayoutLocked() {
        // 三段式结构（ErrorCodeLayout：serviceId=business-content，模块段 2 位，架构设计 4.2 表）
        assertThat(ContentErrorCodes.CONTENT_NOT_FOUND).isEqualTo("business-content-01-0001");
        assertThat(ContentErrorCodes.TITLE_EMPTY).isEqualTo("business-content-01-0002");
        assertThat(ContentErrorCodes.ILLEGAL_TRANSITION).isEqualTo("business-content-01-0003");
        assertThat(ContentErrorCodes.SEARCH_KEYWORD_EMPTY).isEqualTo("business-content-02-0001");
        assertThat(ContentErrorCodes.FEED_TAB_UNSUPPORTED).isEqualTo("business-content-02-0002");
        for (String code : List.of(ContentErrorCodes.CONTENT_NOT_FOUND, ContentErrorCodes.TITLE_EMPTY,
                ContentErrorCodes.ILLEGAL_TRANSITION, ContentErrorCodes.SEARCH_KEYWORD_EMPTY,
                ContentErrorCodes.FEED_TAB_UNSUPPORTED)) {
            ErrorCodeLayout parsed = ErrorCodeLayout.parse(code);
            assertThat(parsed.serviceId()).isEqualTo("business-content");
            assertThat(parsed.code()).isEqualTo(code);
        }
    }

    @Test
    void permissionsLocked() {
        assertThat(ContentPermissions.CONTENT_LIST).isEqualTo("content:content:list");
        assertThat(ContentPermissions.CONTENT_UPDATE).isEqualTo("content:content:update");
        assertThat(ContentPermissions.CONTENT_TRANSITION).isEqualTo("content:content:transition");
        assertThat(ContentPermissions.CONTENT_BATCH_OFFLINE).isEqualTo("content:content:batch-offline");
        assertThat(ContentPermissions.CONTENT_BATCH_DELETE).isEqualTo("content:content:batch-delete");
    }

    @Test
    void wireEnumNamesLocked() {
        // 常量名即 wire 形态（openapi 枚举值；EnumCodeModule name() 兜底口径）
        assertThat(ContentStatus.draft.name()).isEqualTo("draft");
        assertThat(ContentStatus.reviewing.name()).isEqualTo("reviewing");
        assertThat(ContentStatus.published.name()).isEqualTo("published");
        assertThat(ContentStatus.rejected.name()).isEqualTo("rejected");
        assertThat(BatchAction.offline.name()).isEqualTo("offline");
        assertThat(BatchAction.delete.name()).isEqualTo("delete");
        assertThat(LibrarySort.newest.name()).isEqualTo("newest");
        assertThat(LibrarySort.hottest.name()).isEqualTo("hottest");
        assertThat(AdminSortField.title.name()).isEqualTo("title");
        assertThat(AdminSortField.views.name()).isEqualTo("views");
        assertThat(AdminSortField.updatedAt.name()).isEqualTo("updatedAt");
    }

    @Test
    void statusTransitionMatrixLocked() {
        // BR-013 合法迁移集合（架构设计 3.2）：draft→reviewing、reviewing→published/rejected、rejected→draft、published→draft
        assertThat(ContentStatus.draft.canTransitTo(ContentStatus.reviewing)).isTrue();
        assertThat(ContentStatus.reviewing.canTransitTo(ContentStatus.published)).isTrue();
        assertThat(ContentStatus.reviewing.canTransitTo(ContentStatus.rejected)).isTrue();
        assertThat(ContentStatus.rejected.canTransitTo(ContentStatus.draft)).isTrue();
        assertThat(ContentStatus.published.canTransitTo(ContentStatus.draft)).isTrue();
        // 集合外迁移为非法
        assertThat(ContentStatus.draft.canTransitTo(ContentStatus.published)).isFalse();
        assertThat(ContentStatus.draft.canTransitTo(ContentStatus.rejected)).isFalse();
        assertThat(ContentStatus.published.canTransitTo(ContentStatus.reviewing)).isFalse();
        assertThat(ContentStatus.published.canTransitTo(ContentStatus.rejected)).isFalse();
        assertThat(ContentStatus.rejected.canTransitTo(ContentStatus.published)).isFalse();
        assertThat(ContentStatus.rejected.canTransitTo(ContentStatus.reviewing)).isFalse();
        assertThat(ContentStatus.reviewing.canTransitTo(ContentStatus.draft)).isFalse();
    }

    @Test
    void feedTabLenientParseLocked() {
        assertThat(FeedTab.fromWire("recommend")).isEqualTo(FeedTab.recommend);
        assertThat(FeedTab.fromWire("follow")).isEqualTo(FeedTab.follow);
        // 未知值 / 空值返回 null（FR-001：空集不报错）
        assertThat(FeedTab.fromWire("unknown")).isNull();
        assertThat(FeedTab.fromWire("")).isNull();
        assertThat(FeedTab.fromWire(null)).isNull();
    }

    @Test
    void dtoShapesLocked() {
        AppContentItemView item = new AppContentItemView(1L, "标题", "operation", "运营", "摘要");
        assertThat(item.id()).isEqualTo(1L);
        AdminContentRowView row = new AdminContentRowView(1L, "标题", "operation", "运营", "陈序",
                ContentStatus.published, 12580L, Instant.parse("2026-10-24T06:20:00Z"), 3L);
        assertThat(row.views()).isEqualTo(12580L);
        assertThat(row.version()).isEqualTo(3L);
        assertThat(new AdminContentTitleUpdate("新标题", 3L).expectedVersion()).isEqualTo(3L);
        PageResult<String> page = new PageResult<>(List.of("a"), 1L);
        assertThat(page.records()).containsExactly("a");
        assertThat(page.total()).isEqualTo(1L);
        AdminContentBatchResultView batch = new AdminContentBatchResultView(2L, List.of("1"),
                List.of(new AdminBatchFailureView("x", ContentErrorCodes.CONTENT_NOT_FOUND, "内容不存在")));
        assertThat(batch.failures()).hasSize(1);
    }

    @Test
    void longFieldsSerializeAsStringOnWire() {
        // G3 裁定 B：平台 Long→String 全局约定为准（id / views / version / total 出参字符串化）
        AdminContentRowView row = new AdminContentRowView(1234567890123456789L, "标题", "operation", "运营", "陈序",
                ContentStatus.published, 12580L, Instant.parse("2026-10-24T06:20:00Z"), 3L);
        String json = mapper.writeValueAsString(row);
        assertThat(json).contains("\"id\":\"1234567890123456789\"");
        assertThat(json).contains("\"views\":\"12580\"");
        assertThat(json).contains("\"version\":\"3\"");
        // 枚举按名（openapi 小写 wire 值）；时间 ISO-8601
        assertThat(json).contains("\"status\":\"published\"");
        assertThat(json).contains("\"updatedAt\":\"2026-10-24T06:20:00Z\"");
        // PageResult.total 同为字符串 wire（openapi 修订口径：type: string）
        String pageJson = mapper.writeValueAsString(new PageResult<>(List.of(), 25L));
        assertThat(pageJson).contains("\"total\":\"25\"");
        // 请求入参接受字符串与数值两种输入（LongToStringDeserializer 口径）
        AdminContentTitleUpdate back = mapper.readValue("{\"title\":\"t\",\"expectedVersion\":\"7\"}",
                AdminContentTitleUpdate.class);
        assertThat(back.expectedVersion()).isEqualTo(7L);
    }
}
