package com.acme.framework.tenant;

import com.acme.common.context.TenantContext;
import com.acme.framework.core.config.CoreAutoConfiguration;
import com.acme.framework.jpa.config.JpaAutoConfiguration;
import com.acme.framework.tenant.config.TenantAutoConfiguration;
import com.acme.framework.tenant.support.TenantBypassScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @TenantId 自动过滤切片（FR-002/AC-002、FR-003/AC-003；16.1 多租户场景必备）。
 * 事务按方法独立（Hibernate 会话=租户语境，切换租户需新会话）。
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration({CoreAutoConfiguration.class, JpaAutoConfiguration.class, TenantAutoConfiguration.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "acme.jpa.slow-sql.enabled=false",
        "acme.jpa.id.worker-id=1"
})
class TenantFilterDataJpaTest {

    @Autowired
    TenantProbeRepository repository;

    @PersistenceContext
    EntityManager entityManager;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        try (TenantBypassScope ignored = TenantBypassScope.open("test")) {
            repository.deleteAll();
        }
    }

    // ---------- AC-002：写入自动注入 / 查询自动限定 ----------

    @Test
    void persistFillsTenantIdFromContext() {
        TenantContext.bind(new TenantContext("t-1"));
        TenantProbe probe = repository.saveAndFlush(new TenantProbe(1L, "alpha", null));
        assertThat(probe.getTenantId()).as("写入自动填充租户字段（FR-002）").isEqualTo("t-1");
    }

    @Test
    void queryLimitedToCurrentTenant() {
        TenantContext.bind(new TenantContext("t-1"));
        repository.saveAndFlush(new TenantProbe(1L, "alpha", null));
        TenantContext.bind(new TenantContext("t-2"));
        repository.saveAndFlush(new TenantProbe(2L, "beta", null));

        TenantContext.bind(new TenantContext("t-1"));
        List<TenantProbe> seen = repository.findAll();
        assertThat(seen).extracting(TenantProbe::getName).containsExactly("alpha");

        TenantContext.bind(new TenantContext("t-2"));
        assertThat(repository.findAll()).extracting(TenantProbe::getName).containsExactly("beta");
    }

    @Test
    void explicitForeignTenantValueRejected() {
        TenantContext.bind(new TenantContext("t-1"));
        // 显式赋他租户值 → @TenantId 原生语义显式失败（不产生跨租户脏写，FR-002 异常分支）
        assertThatThrownBy(() -> repository.saveAndFlush(new TenantProbe(9L, "dirty", "t-2")))
                .isInstanceOf(RuntimeException.class);
        TenantContext.bind(new TenantContext("t-2"));
        assertThat(repository.findAll()).as("跨租户脏写未发生").isEmpty();
    }

    // ---------- AC-003：旁路访问 ----------

    @Test
    void bypassScopeReadsAcrossTenants() {
        TenantContext.bind(new TenantContext("t-1"));
        repository.saveAndFlush(new TenantProbe(1L, "alpha", null));
        TenantContext.bind(new TenantContext("t-2"));
        repository.saveAndFlush(new TenantProbe(2L, "beta", null));

        TenantContext.bind(new TenantContext("t-1"));
        try (TenantBypassScope ignored = TenantBypassScope.open("test")) {
            List<TenantProbe> all = repository.findAll();
            assertThat(all).as("旁路作用域跨租户出数（AC-003）").extracting(TenantProbe::getName)
                    .containsExactlyInAnyOrder("alpha", "beta");
        }
        assertThat(repository.findAll()).as("关闭旁路后恢复过滤").hasSize(1);
    }

    // ---------- 缺租户 fail-closed（platform-services-bootstrap 修订：拒绝下沉数据操作面） ----------

    @Test
    void persistWithoutTenantRejectedAndNoSentinelRow() {
        // 无租户上下文写入： 绑定哨兵被 UnboundTenantGuard 拒绝（不产生哨兵租户脏行）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> repository.saveAndFlush(new TenantProbe(50L, "no-ctx", null)))
                .isInstanceOf(RuntimeException.class);
        try (TenantBypassScope ignored = TenantBypassScope.open("test")) {
            org.assertj.core.api.Assertions.assertThat(repository.findAll())
                    .as("哨兵租户脏行未落库")
                    .noneMatch(probe -> "no-ctx".equals(probe.getName()));
        }
    }
}
