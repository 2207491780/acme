package com.acme.framework.jpa;

import com.acme.common.context.UserContext;
import com.acme.framework.core.config.CoreAutoConfiguration;
import com.acme.framework.jpa.config.JpaAutoConfiguration;
import com.acme.framework.jpa.support.BatchWriter;
import com.acme.framework.jpa.support.EntityQueries;
import com.acme.framework.jpa.support.FieldRef;
import com.acme.framework.jpa.support.JpaAuditProbe;
import com.acme.framework.jpa.support.JpaAuditProbeRepository;
import com.acme.framework.jpa.support.Op;
import com.acme.framework.jpa.support.QuerySpec;
import com.acme.framework.jpa.support.SortDir;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据访问增强切片测试（FR-008/009/010/011/012，AC-008/009/010；15 章测试设计 2）。
 */
@DataJpaTest
@ImportAutoConfiguration({CoreAutoConfiguration.class, JpaAutoConfiguration.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "acme.jpa.slow-sql.enabled=false",
        "acme.jpa.id.worker-id=1"
})
class JpaDataAccessTest {

    @Autowired
    JpaAuditProbeRepository repository;

    @Autowired
    EntityQueries entityQueries;

    @Autowired
    BatchWriter batchWriter;

    @PersistenceContext
    EntityManager entityManager;

    // ---------- FR-008 审计填充（6.2 缺省口径） ----------

    @Test
    void auditFieldsFilledWithSystemDefault() {
        JpaAuditProbe probe = repository.save(new JpaAuditProbe("alpha", 1L, 1L));
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
            JpaAuditProbe probe = repository.save(new JpaAuditProbe("beta", 2L, 2L));
            assertThat(probe.getCreatedBy()).isEqualTo("u-1");
        } finally {
            UserContext.clear();
        }
    }

    // ---------- FR-009 软删除（6.3） ----------

    @Test
    void softDeleteHidesRowAndKeepsData() {
        JpaAuditProbe probe = repository.save(new JpaAuditProbe("gamma", 3L, 3L));
        Long id = probe.getId();
        Long versionBefore = probe.getVersion();
        repository.deleteById(id);
        assertThat(repository.findById(id)).isEmpty();
        Long remaining = (Long) entityManager
                .createNativeQuery("select count(*) from t_jpa_audit_probe where id = ? and deleted = true")
                .setParameter(1, id)
                .getSingleResult();
        assertThat(remaining).isEqualTo(1L);
        // 软删除同步维护审计链与乐观锁（FR-008/009）：version 递增、updated_* 非空
        Object[] auditRow = (Object[]) entityManager
                .createNativeQuery("select version, updated_at, updated_by from t_jpa_audit_probe where id = ?")
                .setParameter(1, id)
                .getSingleResult();
        assertThat(((Number) auditRow[0]).longValue()).isEqualTo(versionBefore + 1);
        assertThat(auditRow[1]).isNotNull();
        assertThat(auditRow[2]).isNotNull();
    }

    @Test
    void repeatDeleteIsIdempotent() {
        JpaAuditProbe probe = repository.save(new JpaAuditProbe("delta", 4L, 4L));
        Long id = probe.getId();
        repository.deleteById(id);
        // 重复删除幂等（10.3.1、FR-009 异常分支）：已删记录再删按成功处理
        repository.deleteById(id);
        Long remaining = (Long) entityManager
                .createNativeQuery("select count(*) from t_jpa_audit_probe where id = ? and deleted = true")
                .setParameter(1, id)
                .getSingleResult();
        assertThat(remaining).isEqualTo(1L);
    }

    // ---------- FR-010 乐观锁（6.4） ----------

    @Test
    void staleVersionUpdateFails() {
        JpaAuditProbe saved = repository.save(new JpaAuditProbe("epsilon", 5L, 5L));
        Long id = saved.getId();
        // 模拟并发：绕过持久化上下文直接改库版本
        entityManager.createNativeQuery("update t_jpa_audit_probe set version = version + 1 where id = ?")
                .setParameter(1, id)
                .executeUpdate();
        // saved 持有过期版本，更新按并发冲突失败（AC-008 失败语义可判定）
        saved.setName("epsilon-2");
        assertThatThrownBy(() -> repository.saveAndFlush(saved))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ---------- FR-011 查询封装面（Q-D08、AC-009） ----------

    @Test
    void entityQueriesFiltersSortsAndPages() {
        repository.save(new JpaAuditProbe("alpha-1", 10L, 1L));
        repository.save(new JpaAuditProbe("alpha-2", 20L, 2L));
        repository.save(new JpaAuditProbe("alpha-3", 30L, 3L));
        repository.save(new JpaAuditProbe("beta-1", 40L, 4L));

        QuerySpec<JpaAuditProbe> spec = QuerySpec.of(JpaAuditProbe.class)
                .where(FieldRef.of(JpaAuditProbe::getName), Op.STARTS_WITH, "alpha-")
                .where(FieldRef.of(JpaAuditProbe::getAmount), Op.GE, 20L)
                .orderBy(FieldRef.of(JpaAuditProbe::getSeq), SortDir.DESC);

        List<JpaAuditProbe> list = entityQueries.list(JpaAuditProbe.class, spec);
        assertThat(list).extracting(JpaAuditProbe::getName).containsExactly("alpha-3", "alpha-2");

        QuerySpec<JpaAuditProbe> paged = QuerySpec.of(JpaAuditProbe.class)
                .where(FieldRef.of(JpaAuditProbe::getName), Op.STARTS_WITH, "alpha-")
                .orderBy(FieldRef.of(JpaAuditProbe::getSeq), SortDir.ASC)
                .page(1, 2);
        var page = entityQueries.page(JpaAuditProbe.class, paged);
        assertThat(page.total()).isEqualTo(3L);
        assertThat(page.records()).hasSize(2);
        assertThat(page.records().get(0).getName()).isEqualTo("alpha-1");

        assertThat(entityQueries.count(JpaAuditProbe.class, spec)).isEqualTo(2L);
    }

    // ---------- FR-012 批量写入（6.6） ----------

    @Test
    void batchWriterPersistsInChunks() {
        List<JpaAuditProbe> items = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(new JpaAuditProbe("batch-" + i, (long) i, (long) i));
        }
        int written = batchWriter.persistAll(items);
        assertThat(written).isEqualTo(5);
        assertThat(repository.count()).isEqualTo(5);
        assertThat(batchWriter.chunkSize()).isEqualTo(50);
    }
}
