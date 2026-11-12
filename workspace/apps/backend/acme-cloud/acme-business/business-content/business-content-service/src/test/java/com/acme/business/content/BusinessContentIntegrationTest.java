package com.acme.business.content;

import com.acme.business.content.api.AdminSortField;
import com.acme.business.content.api.BatchAction;
import com.acme.business.content.api.ContentStatus;
import com.acme.business.content.api.dto.AdminContentBatchResultView;
import com.acme.business.content.application.CategoryUseCase;
import com.acme.business.content.application.ContentQueryUseCase;
import com.acme.business.content.application.ContentWriter;
import com.acme.business.content.application.SearchUseCase;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.infrastructure.repository.BizContentRepository;
import com.acme.common.contract.PageQuery;
import com.acme.common.core.BizException;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * business-content 集成测试（Testcontainers PostgreSQL，16.2.1 本地可执行；无 Docker 自动跳过）：
 * 实体-脚本一致性（ddl-auto=validate 启动即校验）+ 事务 / 幂等场景（16.1 必备）：
 * 种子重放幂等、改题持久化、状态机持久化、批量下线 / 软删（BR-015 双端不可见）与重复提交幂等。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BusinessContentApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BusinessContentIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("acme").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BizContentRepository contentRepository;
    @Autowired
    private ContentWriter contentWriter;
    @Autowired
    private ContentQueryUseCase contentQueryUseCase;
    @Autowired
    private SearchUseCase searchUseCase;
    @Autowired
    private CategoryUseCase categoryUseCase;

    @Test
    @Order(1)
    void seedBaselineApplied() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from biz_content", Long.class)).isEqualTo(10L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from biz_content_category", Long.class)).isEqualTo(6L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from biz_hot_search_word", Long.class)).isEqualTo(6L);
        assertThat(contentQueryUseCase.feed("recommend")).hasSize(5);
        assertThat(searchUseCase.hotSearches()).containsExactly("内容运营方法论", "AIGC 声明", "同城破圈",
                "选题三角", "私域分发", "季度复盘");
        assertThat(categoryUseCase.list()).hasSize(6);
    }

    @Test
    @Order(2)
    void seedScriptReplayIsIdempotent() throws Exception {
        // 重放 V2 种子（固定主键 + ON CONFLICT 幂等，架构设计 3.6）
        try (java.io.InputStream in = BusinessContentIntegrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V2__seed_content_baseline.sql")) {
            assertThat(in).isNotNull();
            jdbcTemplate.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from biz_content", Long.class)).isEqualTo(10L);
    }

    @Test
    @Order(3)
    void renamePersistsAndSameTitleIsIdempotent() {
        BizContent renamed = contentWriter.rename(2L, "  品牌故事改题  ", 0L);
        assertThat(renamed.getTitle()).isEqualTo("品牌故事改题");
        assertThat(contentRepository.findById(2L)).hasValueSatisfying(c ->
                assertThat(c.getTitle()).isEqualTo("品牌故事改题"));
        // 同标题重复提交 = 同结果（10.3.1 自然幂等）
        contentWriter.rename(2L, "品牌故事改题", null);
        assertThat(contentRepository.findById(2L)).hasValueSatisfying(c ->
                assertThat(c.getTitle()).isEqualTo("品牌故事改题"));
    }

    @Test
    @Order(4)
    void transitionPersistsAndIllegalRejected() {
        BizContent draft = contentWriter.transition(4L, ContentStatus.reviewing);
        assertThat(draft.getStatus()).isEqualTo(ContentStatus.reviewing);
        // 同态幂等
        assertThat(contentWriter.transition(4L, ContentStatus.reviewing).getStatus()).isEqualTo(ContentStatus.reviewing);
        // 集合外迁移拒绝（BR-013：reviewing→draft 不在合法集合）
        assertThatThrownBy(() -> contentWriter.transition(4L, ContentStatus.draft))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("business-content-01-0003"));
    }

    @Test
    @Order(5)
    void batchOfflineUnconditionallyDropsToDraft() {
        // id=3 published → 下线置 draft（QD-06 无条件）；id=4 现为 reviewing → 同样置 draft
        AdminContentBatchResultView result = contentWriter.batch(BatchAction.offline, List.of("3", "4"));
        assertThat(result.failures()).isEmpty();
        assertThat(result.succeededIds()).containsExactly("3", "4");
        assertThat(contentRepository.findById(3L)).hasValueSatisfying(c ->
                assertThat(c.getStatus()).isEqualTo(ContentStatus.draft));
        // 重复提交幂等（已 draft 计成功无效果）
        AdminContentBatchResultView again = contentWriter.batch(BatchAction.offline, List.of("3"));
        assertThat(again.succeededIds()).containsExactly("3");
    }

    @Test
    @Order(6)
    void batchDeleteIsSoftAndHiddenFromAllQueries() {
        AdminContentBatchResultView result = contentWriter.batch(BatchAction.delete, List.of("5", "9999888777"));
        assertThat(result.succeededIds()).containsExactly("5");
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).code()).isEqualTo("business-content-01-0001");
        // BR-015：软删行双端不可见（查询 / 详情 / 联想）
        assertThat(contentRepository.findById(5L)).isEmpty();
        assertThat(contentQueryUseCase.detail(5L)).isEmpty();
        assertThat(contentQueryUseCase.feed("recommend")).noneMatch(c -> c.getId() == 5L);
        // 重复删除幂等（已删行计成功 no-op，架构设计 6.2）
        AdminContentBatchResultView again = contentWriter.batch(BatchAction.delete, List.of("5"));
        assertThat(again.succeededIds()).containsExactly("5");
        assertThat(again.failures()).isEmpty();
    }

    @Test
    @Order(7)
    void adminPageFiltersAndSearchMatch() {
        // keyword 匹配标题或作者（BR-005）；三字段排序（BR-007）；种子作者「陈序」4 行（id 1/3/7/10）
        var page = contentQueryUseCase.adminPage("陈序", null, null, null,
                AdminSortField.views, true, new PageQuery(1, 20));
        assertThat(page.total()).isEqualTo(4L);
        // 搜索：标题/摘要匹配（FR-004），仅 published（BR-002）——「破圈」行为 reviewing 不可见，「复盘」行 published 可见
        var search = searchUseCase.search("复盘", null, new PageQuery(1, 20));
        assertThat(search.records()).hasSize(1);
        assertThat(search.records().get(0).getTitle()).contains("复盘");
        assertThat(searchUseCase.search("破圈", null, new PageQuery(1, 20)).records()).isEmpty();
        // 联想：标题匹配（QD-03）；「投稿」命中 published 的 id=8（前序用例已下线 id=3，选题标题不可见）
        assertThat(searchUseCase.suggestions("投稿")).isNotEmpty();
    }
}
