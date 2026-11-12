package com.acme.framework.tenant;

import com.acme.common.context.TenantContext;
import com.acme.common.core.BizException;
import com.acme.framework.flex.support.AuditBase;
import com.acme.framework.tenant.app.TenantFlexTestApplication;
import com.acme.framework.tenant.app.TenantProbe;
import com.acme.framework.tenant.app.TenantProbeMapper;
import com.acme.framework.tenant.internal.FlexTenantFactory;
import com.acme.framework.tenant.support.TenantBypassScope;
import com.acme.framework.tenant.support.TenantError;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户过滤切片测试（FR-001/002/003、AC-001/003；16.1 多租户场景；H2 等效隔离环境）：
 * 自动过滤、insert 值注入（TenantFactory 覆盖）、缺租户 fail-closed（读空集、写拒绝）与旁路语义。
 */
@SpringBootTest(classes = TenantFlexTestApplication.class, properties = {
        "acme.flex.slow-sql.enabled=false",
        "acme.flex.id.worker-id=1"
})
class TenantFilterFlexTest {

    @Autowired
    TenantProbeMapper mapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        // 共享上下文共享同一 H2 库：每个用例先清租户上下文再物理清空（jdbc 直连不经 mapper 过滤）
        TenantContext.clear();
        jdbcTemplate.update("delete from tenant_probe");
    }

    // ---------- FR-002 @Column(tenantId=true) 自动过滤 + insert 值注入 ----------

    @Test
    void insertInjectsTenantValueAndReadFiltersToOwnTenant() {
        bind("t-1");
        try {
            TenantProbe probe = new TenantProbe("alpha");
            assertThat(mapper.insert(probe)).isEqualTo(1);
            // TenantFactory 返回值自动覆盖实体租户列（官方语义）
            assertThat(probe.getTenantId()).isEqualTo("t-1");

            assertThat(mapper.selectListByQuery(QueryWrapper.create()))
                    .extracting(TenantProbe::getName)
                    .containsExactly("alpha");

            // 其他租户不可见（数据隔离）
            bind("t-2");
            assertThat(mapper.selectCountByQuery(QueryWrapper.create())).isZero();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void updateAndDeleteCarryTenantCondition() {
        bind("t-1");
        try {
            TenantProbe probe = new TenantProbe("beta");
            mapper.insert(probe);

            // 其他租户按主键更新/删除不到该行（条件带 tenant_id）
            bind("t-2");
            probe.setName("beta-x");
            assertThat(mapper.update(probe, false)).isZero();
            assertThat(mapper.deleteById(probe.getId())).isZero();

            // 本租户可以更新
            bind("t-1");
            probe.setName("beta-2");
            assertThat(mapper.update(probe, false)).isEqualTo(1);
            assertThat(mapper.selectOneById(probe.getId()).getName()).isEqualTo("beta-2");
        } finally {
            TenantContext.clear();
        }
    }

    // ---------- 缺租户 fail-closed（读空集、写拒绝） ----------

    @Test
    void unboundReadIsEmptySet() {
        bind("t-1");
        TenantProbe probe = new TenantProbe("gamma");
        mapper.insert(probe);

        TenantContext.clear();
        assertThat(mapper.selectListByQuery(QueryWrapper.create())).isEmpty();
        assertThat(mapper.selectCountByQuery(QueryWrapper.create())).isZero();
        // 未绑定哨兵不跨租户出数：旁路视角下也只应有真实租户数据行
        try (TenantBypassScope ignored = TenantBypassScope.open("test-unbound-read")) {
            assertThat(mapper.selectCountByQuery(QueryWrapper.create())).isEqualTo(1L);
        }
    }

    @Test
    void unboundInsertIsRejectedWithTenantRequired() {
        TenantContext.clear();
        // TenantWriteGuard 经 MyBatis InsertListener 拦截，BizException 被包装为 MyBatisSystemException（root cause 保留错误码）
        assertThatThrownBy(() -> mapper.insert(new TenantProbe("delta")))
                .isInstanceOf(MyBatisSystemException.class)
                .hasRootCauseInstanceOf(BizException.class)
                .satisfies(ex -> {
                    Throwable root = ex;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(((BizException) root).errorCode().code())
                            .isEqualTo(TenantError.TENANT_REQUIRED.code());
                });

        // 不产生哨兵租户脏行：旁路视角无新增行
        bind("t-1");
        try (TenantBypassScope ignored = TenantBypassScope.open("test-unbound-insert")) {
            assertThat(mapper.selectCountByQuery(QueryWrapper.create())).isZero();
        }
    }

    // ---------- FR-003 旁路（12.4.2 显式声明场景） ----------

    @Test
    void bypassScopeReadsAllTenants() {
        bind("t-1");
        TenantProbe first = new TenantProbe("b-1");
        mapper.insert(first);
        bind("t-2");
        TenantProbe second = new TenantProbe("b-2");
        mapper.insert(second);

        // 旁路：忽略租户条件，读到全租户数据
        try (TenantBypassScope ignored = TenantBypassScope.open("test-bypass")) {
            List<TenantProbe> all = mapper.selectListByQuery(QueryWrapper.create());
            assertThat(all).extracting(TenantProbe::getName).containsExactlyInAnyOrder("b-1", "b-2");
        }
    }

    private void bind(String tenantId) {
        TenantContext.bind(new TenantContext(tenantId));
    }
}
