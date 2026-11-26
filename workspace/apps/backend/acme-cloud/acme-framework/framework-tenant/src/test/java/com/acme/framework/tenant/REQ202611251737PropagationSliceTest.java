package com.acme.framework.tenant;

import com.acme.common.context.TenantContext;
import com.acme.framework.tenant.app.TenantFlexTestApplication;
import com.acme.framework.tenant.app.TenantProbe;
import com.acme.framework.tenant.app.TenantProbeMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * REQ-20261125-001737 AC-1 / AC-7 / AC-8 的 H2 切片回归：传播层按确认记录④仅对
 * {@code jdbc:postgresql} 生效，H2 驱动必须自动跳过且不改变现状（不写会话变量、不影响 ORM 过滤面、
 * 不破坏既有直连行为）。装配口径照 {@code TenantFilterFlexTest}（共享上下文共享 H2 库，逐用例物理清表）。
 *
 * <p>本类只承载「H2 自动跳过」的可观测面；AC-1 的 SET LOCAL 语义由 platform-system 的 PG IT
 * {@code REQ202611251737PropagationIntegrationTest} 验证，配置开关 false 由
 * {@code REQ202611251737PropagationDisabledIntegrationTest} 验证。禁止据此类跳过 PG IT。</p>
 */
@SpringBootTest(classes = TenantFlexTestApplication.class, properties = {
        "acme.flex.slow-sql.enabled=false",
        "acme.flex.id.worker-id=1"
})
class REQ202611251737PropagationSliceTest {

    @Autowired
    private TenantProbeMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        jdbcTemplate.update("delete from tenant_probe");
    }

    @Test
    void should_skipPropagationAndKeepDirectSqlUnchanged_when_datasourceIsH2() {
        bind("t-1");
        seedRow(1L, "t-1", "rls-h2-own");
        seedRow(2L, "t-2", "rls-h2-other");

        // H2 上没有 RLS：直连 SQL 仍见全量（现状一致，传播层不得把 PG 语义泄漏到 H2）
        List<String> direct = jdbcTemplate.queryForList(
                "select name from tenant_probe order by id", String.class);
        assertThat(direct).as("H2 直连应维持现状：不做租户过滤").containsExactly("rls-h2-own", "rls-h2-other");

        // ORM 既有租户过滤面不受影响
        assertThat(mapper.selectListByQuery(QueryWrapper.create()))
                .extracting(TenantProbe::getName)
                .containsExactly("rls-h2-own");

        // 绑定/解绑租户不应触发任何 PG 专属语句（H2 上执行失败即回归）
        assertThatCode(() -> {
            bind("t-2");
            assertThat(mapper.selectCountByQuery(QueryWrapper.create())).isEqualTo(1L);
            TenantContext.clear();
            assertThat(mapper.selectCountByQuery(QueryWrapper.create())).isZero();
        }).as("H2 上租户上下文切换不得因传播层报错").doesNotThrowAnyException();
    }

    private void bind(String tenantId) {
        TenantContext.bind(new TenantContext(tenantId));
    }

    private void seedRow(long id, String tenantId, String name) {
        jdbcTemplate.update("""
                insert into tenant_probe (id, tenant_id, name, created_at, created_by, updated_at, updated_by,
                                          version, deleted)
                values (?, ?, ?, current_timestamp, 'req1737-it', current_timestamp, 'req1737-it', 0, false)
                """, id, tenantId, name);
    }
}
