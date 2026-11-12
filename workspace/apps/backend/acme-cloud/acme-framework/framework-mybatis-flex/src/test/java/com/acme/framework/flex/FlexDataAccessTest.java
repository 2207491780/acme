package com.acme.framework.flex;

import com.acme.common.context.UserContext;
import com.acme.framework.flex.app.FlexAuditProbe;
import com.acme.framework.flex.app.FlexAuditProbeMapper;
import com.acme.framework.flex.app.FlexTestApplication;
import com.acme.framework.flex.internal.FlexDeletes;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据访问增强切片测试（FR-008/009/010，AC-008；15 章测试设计 2；H2 等效隔离环境）。
 */
@SpringBootTest(classes = FlexTestApplication.class, properties = {
        "acme.flex.slow-sql.enabled=false",
        "acme.flex.id.worker-id=1",
        "mybatis-flex.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl"
})
class FlexDataAccessTest {

    @Autowired
    FlexAuditProbeMapper mapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        // 共享上下文共享同一 H2 库：每个用例物理清空，保证断言互不干扰
        jdbcTemplate.update("delete from flex_audit_probe");
    }

    // ---------- FR-008 审计填充（6.2 缺省口径） ----------

    @Test
    void auditFieldsFilledWithSystemDefault() {
        FlexAuditProbe probe = insert(new FlexAuditProbe("alpha", 1L, 1L));
        assertThat(probe.getCreatedAt()).isNotNull();
        assertThat(probe.getUpdatedAt()).isNotNull();
        assertThat(probe.getCreatedBy()).isEqualTo("system");
        assertThat(probe.getUpdatedBy()).isEqualTo("system");
        assertThat(probe.getId()).isNotNull();
        assertThat(probe.getDeleted()).isFalse();
        assertThat(probe.getVersion()).isNotNull();
    }

    @Test
    void auditOperatorFromUserContext() {
        UserContext.bind(new UserContext("u-1", java.util.Set.of("admin")));
        try {
            FlexAuditProbe probe = insert(new FlexAuditProbe("beta", 2L, 2L));
            assertThat(probe.getCreatedBy()).isEqualTo("u-1");
            assertThat(probe.getUpdatedBy()).isEqualTo("u-1");
        } finally {
            UserContext.clear();
        }
    }

    // ---------- FR-010 乐观锁（6.4） ----------

    @Test
    void updateFillsAuditAndIncrementsVersion() {
        FlexAuditProbe probe = insert(new FlexAuditProbe("gamma", 3L, 3L));
        probe.setName("gamma-2");
        int rows = mapper.update(probe, false);
        assertThat(rows).isEqualTo(1);
        assertThat(probe.getUpdatedAt()).isNotNull();
        assertThat(probe.getUpdatedBy()).isEqualTo("system");
        // Flex 在 SQL 端自增 version（version = version + 1），不写回实体；仓储层负责实体版本同步
        FlexAuditProbe reloaded = mapper.selectOneById(probe.getId());
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getName()).isEqualTo("gamma-2");
    }

    @Test
    void staleVersionUpdateReturnsZeroRows() {
        FlexAuditProbe probe = insert(new FlexAuditProbe("epsilon", 5L, 5L));
        // 模拟并发：绕过实体直接改库版本
        jdbcTemplate.update("update flex_audit_probe set version = version + 1 where id = ?", probe.getId());
        probe.setName("epsilon-2");
        // saved 持有过期版本，更新 0 行（AC-008 失败语义可判定；仓储层转 FlexError.STALE_WRITE）
        assertThat(mapper.update(probe, false)).isZero();
    }

    // ---------- FR-009 软删除（6.3） ----------

    @Test
    void softDeleteHidesRowAndKeepsData() {
        FlexAuditProbe probe = insert(new FlexAuditProbe("zeta", 6L, 6L));
        Long id = probe.getId();
        assertThat(FlexDeletes.deleteById(mapper, id)).isEqualTo(1);
        assertThat(mapper.selectOneById(id)).isNull();
        FlexAuditProbe bypass = FlexDeletes.execWithoutLogicDelete(() -> mapper.selectOneById(id));
        assertThat(bypass).isNotNull();
        assertThat(bypass.getDeleted()).isTrue();
    }

    @Test
    void repeatDeleteIsIdempotent() {
        FlexAuditProbe probe = insert(new FlexAuditProbe("delta", 4L, 4L));
        Long id = probe.getId();
        assertThat(FlexDeletes.deleteById(mapper, id)).isEqualTo(1);
        // 重复删除幂等（10.3.1、FR-009 异常分支）：已删记录再删 0 行按成功处理
        assertThat(FlexDeletes.deleteById(mapper, id)).isZero();
        Long deletedCount = jdbcTemplate.queryForObject(
                "select count(*) from flex_audit_probe where id = ? and deleted = true", Long.class, id);
        assertThat(deletedCount).isEqualTo(1L);
    }

    @Test
    void logicalDeleteFiltersAllQueries() {
        FlexAuditProbe kept = insert(new FlexAuditProbe("kept", 10L, 10L));
        FlexAuditProbe gone = insert(new FlexAuditProbe("gone", 11L, 11L));
        FlexDeletes.deleteById(mapper, gone.getId());

        assertThat(mapper.selectCountByQuery(QueryWrapper.create())).isEqualTo(1L);
        List<FlexAuditProbe> list = mapper.selectListByQuery(QueryWrapper.create());
        assertThat(list).extracting(FlexAuditProbe::getName).containsExactly("kept");
        assertThat(mapper.selectOneById(gone.getId())).isNull();
        assertThat(mapper.selectOneById(kept.getId())).isNotNull();
    }

    @Test
    void softDeleteKeepsVersionAndAuditColumnsUntouched() {
        // 软删除=单列原子 UPDATE（与原 Hibernate 单列 update 口径一致）：不递增 version、不刷新
        // updated_at/by（无 UpdateListener 参与，与常规实体更新路径不同），锚定防漂移
        FlexAuditProbe probe = insert(new FlexAuditProbe("keep", 20L, 20L));
        Timestamp updatedAtBefore = jdbcTemplate.queryForObject(
                "select updated_at from flex_audit_probe where id = ?", Timestamp.class, probe.getId());
        assertThat(FlexDeletes.deleteById(mapper, probe.getId())).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select version, updated_at, updated_by, deleted from flex_audit_probe where id = ?", probe.getId());
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(row.get("updated_at")).isEqualTo(updatedAtBefore);
        assertThat(row.get("updated_by")).isEqualTo("system");
        assertThat(row.get("deleted")).isEqualTo(Boolean.TRUE);
    }

    // ---------- 查询封装（QueryWrapper 过滤/排序/分页） ----------

    @Test
    void queryWrapperFiltersSortsAndPaginates() {
        insert(new FlexAuditProbe("alpha-1", 10L, 1L));
        insert(new FlexAuditProbe("alpha-2", 20L, 2L));
        insert(new FlexAuditProbe("alpha-3", 30L, 3L));
        insert(new FlexAuditProbe("beta-1", 40L, 4L));

        QueryWrapper query = QueryWrapper.create()
                .where(FlexAuditProbe::getName).like("alpha-");
        query.and(FlexAuditProbe::getAmount).ge(20L);
        query.orderBy(FlexAuditProbe::getSeq, false);

        List<FlexAuditProbe> list = mapper.selectListByQuery(query);
        assertThat(list).extracting(FlexAuditProbe::getName).containsExactly("alpha-3", "alpha-2");
        // 计数用独立条件（不带排序：count 查询含 ORDER BY 在多数数据库非法）
        QueryWrapper count = QueryWrapper.create()
                .where(FlexAuditProbe::getName).like("alpha-")
                .and(FlexAuditProbe::getAmount).ge(20L);
        assertThat(mapper.selectCountByQuery(count)).isEqualTo(2L);

        QueryWrapper paged = QueryWrapper.create()
                .where(FlexAuditProbe::getName).like("alpha-")
                .orderBy(FlexAuditProbe::getSeq, true);
        var page = mapper.paginate(1, 2, paged);
        assertThat(page.getTotalRow()).isEqualTo(3L);
        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getRecords().get(0).getName()).isEqualTo("alpha-1");
    }

    // ---------- 软删除开关读取 ----------

    @Test
    void flexDeletesSoftDeleteEnabledDefaultsTrue() {
        assertThat(FlexDeletes.softDeleteEnabled()).isTrue();
    }

    private FlexAuditProbe insert(FlexAuditProbe probe) {
        assertThat(mapper.insert(probe)).isEqualTo(1);
        return probe;
    }
}
