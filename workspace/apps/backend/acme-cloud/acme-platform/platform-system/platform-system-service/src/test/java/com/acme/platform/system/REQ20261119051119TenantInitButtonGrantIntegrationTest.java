package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.platform.system.api.dto.AdminTenantInitRequest;
import com.acme.platform.system.application.TenantInitUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

/**
 * REQ-20261119-051119 租户开通 BUTTON 授予面集成测试（Testcontainers PG，装配口径照抄
 * {@link SystemIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖 <b>AC-6</b>（契约回填锚点）：开通后租户管理员自动获 BUTTON 节点绑定与对应权限
 * （既有 grantable 语义，TenantInitUseCase 不改）——真实 PG 面验证：</p>
 * <ul>
 *   <li>租户管理员 role_menu 含非保留域 BUTTON 节点（users 创建按钮为契约确认①示例锚点）；</li>
 *   <li>role_permission 含对应权限点（绑定的每个 BUTTON 节点其 permission_code 均已授）；</li>
 *   <li>保留域 BUTTON 节点（system:tenant / infra:audit 域，契约「非保留域才自动授予」）
 *       不落入租户管理员绑定。</li>
 * </ul>
 *
 * <p>REQ-20261121-022910 增补：{@code system:tenant-package:} 入保留域（套餐管理=平台运营域，
 * TL 裁决收紧 TenantInitUseCase.RESERVED_PERMISSION_PREFIXES）——V24 种子的套餐管理叶 46 与
 * BUTTON 47~49、权限点 69~72 均不落入 tenant_admin 默认授权（真实种子面钉住）。</p>
 *
 * <p>与 backend 单测的关系：确认⑥要求 {@code TenantInitUseCaseTest} 补 BUTTON 节点授予断言
 * （mock 面）；本类是真实种子数据 + 真实落库的集成面，两者互补。签名照抄 TenantInitUseCaseTest
 * 既有 fixture（initialize(AdminTenantInitRequest) 五参构造：租户码/租户名/备注/管理员账号/角色名）。</p>
 *
 * <p>签名假设见契约回填 AMB 清单（AMB-1/2/3/11）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req051119-init-secret-0123456789abcd"
})
class REQ20261119051119TenantInitButtonGrantIntegrationTest {

    /** 开通目标租户（fixture 口径：initialize 内部切换目标租户上下文并在结束后恢复 default）。 */
    private static final String NEW_TENANT = "req051119-acme";
    private static final String NEW_ADMIN_USERNAME = "req051119_admin";

    /** 契约确认①示例锚点：users 创建按钮节点挂既有 API 码 system:user:create（AMB-1）。 */
    private static final String USERS_CREATE_CODE = "system:user:create";

    /** 保留域（TenantInitUseCaseTest 既有 fixtures：system:tenant / infra:audit；REQ-20261121-022910
     *  增 system:tenant-package）——BUTTON 亦按域排除。 */
    private static final String RESERVED_DOMAIN_CLAUSE =
            "(m.permission_code like 'system:tenant:%' or m.permission_code like 'infra:audit:%' "
                    + "or m.permission_code like 'system:tenant-package:%')";

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
    private TenantInitUseCase tenantInitUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        // 开通落库行 teardown（绑定表物理删；租户归属行按测试租户清理，含失败路径残留）
        jdbcTemplate.update("delete from sys_user_role where tenant_id = ?", NEW_TENANT);
        jdbcTemplate.update("delete from sys_user_role where user_id in "
                + "(select id from sys_user where username = ?)", NEW_ADMIN_USERNAME);
        jdbcTemplate.update("delete from sys_user where tenant_id = ?", NEW_TENANT);
        jdbcTemplate.update("delete from sys_user where username = ?", NEW_ADMIN_USERNAME);
        jdbcTemplate.update("delete from sys_role_menu where tenant_id = ?", NEW_TENANT);
        jdbcTemplate.update("delete from sys_role_permission where tenant_id = ?", NEW_TENANT);
        jdbcTemplate.update("delete from sys_role where tenant_id = ?", NEW_TENANT);
        jdbcTemplate.update("delete from sys_tenant where tenant_code = ?", NEW_TENANT);
        TenantContext.clear();
        UserContext.clear();
    }

    // ---------- AC-6：开通后租户管理员自动获 BUTTON 绑定与对应权限（保留域除外） ----------

    @Test
    void should_grantButtonNodesAndPermissions_when_tenantInitialized() {
        TenantContext.bind(new TenantContext("default"));
        var result = tenantInitUseCase.initialize(new AdminTenantInitRequest(
                NEW_TENANT, "REQ051119 集成租户", null, NEW_ADMIN_USERNAME, "租户管理员"));

        assertThat(result.tenantCode()).isEqualTo(NEW_TENANT);
        assertThat(result.adminUsername()).isEqualTo(NEW_ADMIN_USERNAME);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant where tenant_code = ?", Long.class, NEW_TENANT))
                .as("开通落库 sys_tenant 行").isEqualTo(1L);

        // 开通路径为目标租户创建唯一管理员角色（不钉 role_code，按租户定位——AMB-11）
        List<Long> roleIds = jdbcTemplate.queryForList(
                "select id from sys_role where tenant_id = ?", Long.class, NEW_TENANT);
        assertThat(roleIds).as("目标租户应恰有开通创建的管理员角色").hasSize(1);
        Long roleId = roleIds.get(0);

        // role_menu 含非保留域 BUTTON 节点（users 创建按钮为契约确认①清单内锚点）
        List<Long> userCreateButtons = jdbcTemplate.queryForList(
                "select id from sys_menu where menu_type = 'BUTTON' and permission_code = ?",
                Long.class, USERS_CREATE_CODE);
        assertThat(userCreateButtons).as("V20 应含 users 创建 BUTTON 节点（AMB-1）").hasSize(1);
        Long buttonMenuId = userCreateButtons.get(0);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu where tenant_id = ? and role_id = ? and menu_id = ?",
                Long.class, NEW_TENANT, roleId, buttonMenuId))
                .as("租户管理员 role_menu 应含 BUTTON 节点（users 创建）").isEqualTo(1L);

        // role_permission 含对应权限点（BUTTON → permission_code → sys_permission.id）
        Long permissionId = jdbcTemplate.queryForObject(
                "select id from sys_permission where permission_code = ?", Long.class, USERS_CREATE_CODE);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_permission where tenant_id = ? and role_id = ? and permission_id = ?",
                Long.class, NEW_TENANT, roleId, permissionId))
                .as("租户管理员 role_permission 应含按钮对应权限点").isEqualTo(1L);

        // 绑定一致性：每个已绑 BUTTON 节点的权限点都在 role_permission（无「绑菜单未授码」缺口）
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_role_menu rm
                join sys_menu m on m.id = rm.menu_id
                left join sys_permission p on p.permission_code = m.permission_code
                where rm.tenant_id = ? and rm.role_id = ? and m.menu_type = 'BUTTON'
                  and (p.id is null
                       or not exists (select 1 from sys_role_permission rp
                                      where rp.tenant_id = rm.tenant_id and rp.role_id = rm.role_id
                                        and rp.permission_id = p.id))
                """, Long.class, NEW_TENANT, roleId))
                .as("绑定的 BUTTON 节点与 role_permission 必须一一对应").isZero();

        // 保留域 BUTTON 不授予（既有 grantable 语义：非保留域才自动授予）
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_role_menu rm
                join sys_menu m on m.id = rm.menu_id
                where rm.tenant_id = ? and rm.role_id = ? and m.menu_type = 'BUTTON'
                  and %s
                """.formatted(RESERVED_DOMAIN_CLAUSE), Long.class, NEW_TENANT, roleId))
                .as("保留域（system:tenant / infra:audit / system:tenant-package）BUTTON 节点不落入租户管理员绑定")
                .isZero();

        // REQ-20261121-022910：套餐管理域（V24 菜单 46~49 / 权限点 69~72）不落入 tenant_admin 默认授权
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu where tenant_id = ? and role_id = ? and menu_id between 46 and 49",
                Long.class, NEW_TENANT, roleId))
                .as("套餐管理菜单 46~49（MENU 叶+BUTTON）不落入 tenant_admin role_menu").isZero();
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_role_permission rp join sys_permission p on p.id = rp.permission_id "
                        + "where rp.tenant_id = ? and rp.role_id = ? and p.permission_code like 'system:tenant-package:%'",
                String.class, NEW_TENANT, roleId))
                .as("system:tenant-package:* 权限点（69~72）不落入 tenant_admin role_permission").isEmpty();
    }
}
