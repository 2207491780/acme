package com.acme.platform.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261119-051119 按钮级权限 V20 迁移面集成测试（Testcontainers PG，装配口径照抄
 * {@link SystemIntegrationTest}；无 Docker 自动跳过）。
 *
 * <p>覆盖 <b>AC-1</b>（契约回填锚点）：V20 后 BUTTON 节点入 sys_menu 且 permission_code
 * 均为既有 API 权限点（join 校验存在、类型 API、启用未删）、超管 role_menu 全绑、
 * 权限点总数 82（V20 前恒 68 不新建；V24 套餐域新增 4 点 69~72，REQ-20261121-022910；
 * V26 调度域新增 5 点 73~77，REQ-20261121-071002；
 * V27 工作流域新增 5 点 78~82，REQ-20261121-142316）；另含 V20 种子
 * 幂等重放（口径同 V19：固定主键 + ON CONFLICT (id) DO NOTHING）。</p>
 *
 * <p>与 {@link SystemIntegrationTest} 计数断言的口径一致性（确认⑥联动说明）：</p>
 * <ul>
 *   <li>{@code SystemIntegrationTest.flywaySeedPresent} 的 {@code sys_menu == 12} 由 backend
 *       按「12→实际数」联动更新；本类不重复钉 sys_menu 总数，只断「总数 = 非 BUTTON 基数 + BUTTON 数」
 *       （基数 12→18→19：REQ-20261119-151640 V22 新增六 MENU 叶子；V20 仅新增 BUTTON 菜单行；
 *       REQ-20261121-022910 V24 新增套餐管理 MENU 叶 46），两套口径互不冲突。</li>
 *   <li>V20 不新增 sys_permission / sys_role_permission 行；V24 新增 permission 69~72 与
 *       role_permission（permission_id 69~72，均 ≥46/≥54）；V26 新增 permission 73~77 与超管
 *       绑定行（id 77~81）；V27 新增 permission 78~82 与超管绑定行（id 82~86，均 ≥46/≥54，
 *       REQ-20261121-142316）→ {@code seedScriptReplayIsIdempotent}
 *       的 permission=82、role_permission(>=46)=37、(>=54)=29 断言（replay 列表加 V20/V24/V26/V27
 *       由 backend 在其自有文件内联动）；本类独立重放 V20（每测试类独立 PG 容器，互不影响）。</li>
 *   <li>BUTTON 精确行数不钉死（契约「约 22 个、以各页面实际 crud.can 调用码为准实地采集」），
 *       仅断 &gt;0 与形状校验；具体码与前端页面接线一致性由前端 AC-5 用例核验。</li>
 * </ul>
 *
 * <p>签名假设见契约回填 AMB 清单（AMB-1/6/7/8/9）：V20 文件名 {@code V20__button_menu_nodes.sql}、
 * 幂等口径同 V19、BUTTON 行 path/component/icon 为空且无子节点、超管 role_id=1/tenant 'default'。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req051119-migration-secret-0123456789ab"
})
class REQ20261119051119ButtonMenuMigrationTest {

    /** 非 BUTTON 菜单基数＝V19 用至 12＋REQ-20261119-151640 V22 六 MENU 叶子（id 40~45）
     *  ＋REQ-20261121-022910 V24 套餐管理 MENU 叶（id 46）＝19。 */
    private static final long SEED_MENU_BASE_COUNT = 19L;

    /** 超管角色（V2 种子：role_id=1 / tenant 'default'，V19 绑定行续编先例）。 */
    private static final long SUPER_ADMIN_ROLE_ID = 1L;
    private static final String DEFAULT_TENANT = "default";

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

    // ---------- AC-1：BUTTON 节点入 sys_menu，permission_code 均为既有 API 权限点（V27 水位 82） ----------

    @Test
    void should_seedButtonNodesAsApiCodeProjections_when_flywayAppliedToV20() {
        long buttonCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class);
        assertThat(buttonCount)
                .as("V20 应种入 BUTTON 节点（契约约 22 个，实地采集口径不钉精确数）").isPositive();

        // permission_code 必须命中既有权限点：存在、类型 API、启用、未删（投影语义）
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_menu m
                left join sys_permission p on p.permission_code = m.permission_code
                where m.menu_type = 'BUTTON'
                  and (m.permission_code is null or m.permission_code = ''
                       or p.id is null or p.deleted
                       or p.permission_type <> 'API' or p.status <> 'ENABLED')
                """, Long.class))
                .as("每个 BUTTON 节点的 permission_code 必须是既有 API 权限点（不新建权限点）").isZero();

        // 契约确认①：BUTTON 节点 path/component/icon 空（无路由语义）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON' "
                        + "and (path is not null or component is not null or icon is not null)", Long.class))
                .as("BUTTON 节点 path/component/icon 应为空").isZero();

        // BUTTON 为叶子（变更清单：EntityViews 树组装按 BUTTON 无子节点确认）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu child join sys_menu parent on child.parent_id = parent.id "
                        + "where parent.menu_type = 'BUTTON'", Long.class))
                .as("BUTTON 节点不应有子节点").isZero();

        // 确认①：菜单 id 自 13 起续编（V19 用至 12）
        assertThat(jdbcTemplate.queryForObject(
                "select min(id) from sys_menu where menu_type = 'BUTTON'", Long.class))
                .as("BUTTON 菜单 id 自 13 起续编").isGreaterThanOrEqualTo(13L);

        // 权限点总数 82（V20 前恒 68 不新建；V24 套餐域新增 4 点 69~72，BUTTON 47~49 关联之；
        // V26 调度域新增 5 点 73~77，REQ-20261121-071002；V27 工作流域新增 5 点 78~82，
        // REQ-20261121-142316。水位为绝对计数、随新增权限点的 REQ
        // 抬升——总数来源见 SystemIntegrationTest 同源口径，勿在各自用例重复钉基数演变史）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class))
                .as("sys_permission 总数 = 68 基线 + V24 套餐域 4 点 + V26 调度域 5 点 + V27 工作流域 5 点")
                .isEqualTo(82L);

        // 总数 = 非 BUTTON 基数 18（原 12＋V22 六叶子）+ BUTTON（与 SystemIntegrationTest 的基数→N 联动口径一致）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .as("sys_menu 总数 = 种子基数 18（含 REQ-20261119-151640 V22 六叶子）+ BUTTON 数")
                .isEqualTo(SEED_MENU_BASE_COUNT + buttonCount);

        // menu_type 值域：既有两值 + 新增 BUTTON
        assertThat(jdbcTemplate.queryForList("select distinct menu_type from sys_menu", String.class))
                .contains("BUTTON")
                .isSubsetOf("DIRECTORY", "MENU", "BUTTON");
    }

    // ---------- AC-1：超管 role_menu 全绑 BUTTON 节点 ----------

    @Test
    void should_bindAllButtonNodesToSuperAdmin_when_flywayAppliedToV20() {
        long buttonCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class);
        assertThat(buttonCount).as("前置：V20 已种入 BUTTON 节点").isPositive();

        // 超管（role_id=1，default 租户）覆盖全部 BUTTON 节点（确认①：超管全绑）
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_menu m
                where m.menu_type = 'BUTTON'
                  and not exists (select 1 from sys_role_menu rm
                                  where rm.menu_id = m.id and rm.role_id = %d and rm.tenant_id = '%s')
                """.formatted(SUPER_ADMIN_ROLE_ID, DEFAULT_TENANT), Long.class))
                .as("超管 role_menu 必须覆盖全部 BUTTON 节点").isZero();

        // 确认①：role_menu 绑定行 id 自 13 起续编（V19 用至 12）
        assertThat(jdbcTemplate.queryForObject("""
                select min(rm.id) from sys_role_menu rm
                join sys_menu m on m.id = rm.menu_id
                where m.menu_type = 'BUTTON'
                """, Long.class))
                .as("BUTTON 的 role_menu 绑定行 id 自 13 起续编").isGreaterThanOrEqualTo(13L);
    }

    // ---------- AC-1：V20 种子幂等重放（口径同 V19：固定主键 + ON CONFLICT (id) DO NOTHING） ----------

    @Test
    void should_keepCountsStable_when_v20SeedInsertsReplayed() {
        long buttonBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class);
        long superAdminButtonBindingBefore = jdbcTemplate.queryForObject("""
                select count(*) from sys_role_menu rm
                join sys_menu m on m.id = rm.menu_id
                where m.menu_type = 'BUTTON' and rm.role_id = %d and rm.tenant_id = '%s'
                """.formatted(SUPER_ADMIN_ROLE_ID, DEFAULT_TENANT), Long.class);
        long permissionBefore = jdbcTemplate.queryForObject(
                "select count(*) from sys_permission", Long.class);
        long menuTotalBefore = jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class);

        replaySeedInserts("db/migration/system/V20__button_menu_nodes.sql");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type = 'BUTTON'", Long.class))
                .as("重放 V20 后 BUTTON 行不翻倍").isEqualTo(buttonBefore);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sys_role_menu rm
                join sys_menu m on m.id = rm.menu_id
                where m.menu_type = 'BUTTON' and rm.role_id = %d and rm.tenant_id = '%s'
                """.formatted(SUPER_ADMIN_ROLE_ID, DEFAULT_TENANT), Long.class))
                .as("重放 V20 后超管 BUTTON 绑定行不翻倍").isEqualTo(superAdminButtonBindingBefore);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class))
                .as("重放 V20 不新增权限点（总数=V27 水位 82）").isEqualTo(permissionBefore).isEqualTo(82L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_menu", Long.class))
                .as("重放 V20 后 sys_menu 总数不变").isEqualTo(menuTotalBefore);
    }

    // ---------- 支撑（照抄 SystemIntegrationTest.replaySeedInserts 口径） ----------

    /** 按分号切分迁移脚本、剔除 -- 注释行后仅重放 INSERT 语句（种子值不含分号字面量）。 */
    private void replaySeedInserts(String path) {
        for (String chunk : readResource(path).split(";")) {
            String statement = chunk.lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(java.util.stream.Collectors.joining("\n"))
                    .strip();
            if (statement.regionMatches(true, 0, "insert", 0, 6)) {
                jdbcTemplate.execute(statement);
            }
        }
    }

    private static String readResource(String path) {
        try (java.io.InputStream in = REQ20261119051119ButtonMenuMigrationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("资源缺失：%s", path).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
