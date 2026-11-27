package com.acme.platform.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261119-151640 AC-1/2/3/7/8：V22 六页菜单叶子种子＋BUTTON 归位回归。
 * 期望值全部取自契约 contracts/REQ-20261119-151640.md §变更清单明细表与确认记录①~④，
 * 不从实现反推。装配照 {@code MenuPathAlignmentIntegrationTest}（Testcontainers PG，
 * 无 Docker 自动跳过；跳过≠验证，合并前须在有 Docker 环境跑过）。
 * V22 SQL 由并行工人产出，本测试仅按 classpath 路径运行时消费（重放幂等口径同 V13 范本）。
 */
class REQ20261119151640MenuLeavesSeedIntegrationTest extends AbstractSystemIntegrationTest {

    private static final String V22_SCRIPT = "db/migration/system/V22__menu_leaves_six_pages.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private Map<String, Object> menuRow(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM sys_menu WHERE id = ?", id);
    }

    private long count(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    private void replayV22() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(V22_SCRIPT));
        }
    }

    /** 契约明细表逐字段断言（含 §变更清单给定的 menu_type/created_by/updated_by/version/deleted）。 */
    private void assertLeaf(long id, long parentId, String menuName, String path, String component,
                            String icon, int sortOrder, String permissionCode) {
        Map<String, Object> row = menuRow(id);
        assertThat(((Number) row.get("parent_id")).longValue()).isEqualTo(parentId);
        assertThat(row.get("menu_name")).isEqualTo(menuName);
        assertThat(row.get("menu_type")).isEqualTo("MENU");
        assertThat(row.get("path")).isEqualTo(path);
        assertThat(row.get("component")).isEqualTo(component);
        assertThat(row.get("icon")).isEqualTo(icon);
        assertThat(((Number) row.get("sort_order")).intValue()).isEqualTo(sortOrder);
        assertThat(row.get("permission_code")).isEqualTo(permissionCode);
        assertThat(row.get("visible")).isEqualTo(true);
        assertThat(row.get("status")).isEqualTo("ENABLED");
        assertThat(row.get("created_by")).isEqualTo("system");
        assertThat(row.get("updated_by")).isEqualTo("system");
        assertThat(((Number) row.get("version")).longValue()).isZero();
        assertThat(row.get("deleted")).isEqualTo(false);
    }

    @Test
    void should_seed_six_menu_leaves_with_contract_fields_when_v22_applied() {
        // AC-1：明细表六行（确认①目录归属＋确认③排序追加目录末尾）
        assertLeaf(40, 1, "部门管理", "/depts", "system/dept/index", "depts", 16, "system:dept:list");
        assertLeaf(41, 1, "岗位管理", "/positions", "system/position/index", "positions", 17, "system:position:list");
        assertLeaf(42, 1, "通知公告", "/notices", "system/notice/index", "notification", 18, "system:notice:list");
        assertLeaf(43, 8, "文件管理", "/files", "infra/file/index", "files", 5, "infra:file:list");
        assertLeaf(44, 8, "登录日志", "/login-logs", "auth/loginlog/index", "loginlogs", 6, "auth:loginlog:list");
        assertLeaf(45, 8, "在线用户", "/online-users", "auth/online/index", "onlineusers", 7, "auth:online:list");
        assertThat(count("SELECT COUNT(*) FROM sys_menu WHERE id BETWEEN 40 AND 45")).isEqualTo(6L);
    }

    @Test
    void should_bind_six_leaves_to_superadmin_only_when_v22_applied() {
        // AC-2：行 id 40~45 ↔ menu_id 40~45，role_id=1（确认②），tenant_id='default'
        for (long id = 40; id <= 45; id++) {
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM sys_role_menu WHERE id = ?", id);
            assertThat(((Number) row.get("role_id")).longValue()).isEqualTo(1L);
            assertThat(((Number) row.get("menu_id")).longValue()).isEqualTo(id);
            assertThat(row.get("tenant_id")).isEqualTo("default");
        }
        // 新叶子绑定仅超管：无重复行、无其他角色行（其他角色既有绑定不变由重放用例快照比对兜底）
        assertThat(count("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id BETWEEN 40 AND 45")).isEqualTo(6L);
        assertThat(count("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id BETWEEN 40 AND 45 AND role_id <> 1"))
                .isZero();
    }

    @Test
    void should_be_idempotent_when_v22_script_replayed() throws Exception {
        // AC-3：INSERT ON CONFLICT DO NOTHING 不改既有行；重放后行数与关键行内容不变
        long menuCountBefore = count("SELECT COUNT(*) FROM sys_menu");
        long roleMenuCountBefore = count("SELECT COUNT(*) FROM sys_role_menu");
        List<Map<String, Object>> leavesBefore =
                jdbcTemplate.queryForList("SELECT * FROM sys_menu WHERE id BETWEEN 40 AND 45 ORDER BY id");
        List<Map<String, Object>> otherRoleBindingsBefore =
                jdbcTemplate.queryForList("SELECT * FROM sys_role_menu WHERE role_id <> 1 ORDER BY id");
        List<Map<String, Object>> buttonBindingsBefore =
                jdbcTemplate.queryForList("SELECT * FROM sys_role_menu WHERE menu_id BETWEEN 30 AND 38 ORDER BY id");

        replayV22();

        assertThat(count("SELECT COUNT(*) FROM sys_menu")).isEqualTo(menuCountBefore);
        assertThat(count("SELECT COUNT(*) FROM sys_role_menu")).isEqualTo(roleMenuCountBefore);
        assertThat(count("SELECT COUNT(*) FROM sys_menu WHERE id BETWEEN 40 AND 45")).isEqualTo(6L);
        assertThat(count("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id BETWEEN 40 AND 45")).isEqualTo(6L);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM sys_menu WHERE id BETWEEN 40 AND 45 ORDER BY id"))
                .isEqualTo(leavesBefore);
        // AC-2/AC-7 的「不受影响」在重放面同样成立
        assertThat(jdbcTemplate.queryForList("SELECT * FROM sys_role_menu WHERE role_id <> 1 ORDER BY id"))
                .isEqualTo(otherRoleBindingsBefore);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM sys_role_menu WHERE menu_id BETWEEN 30 AND 38 ORDER BY id"))
                .isEqualTo(buttonBindingsBefore);
    }

    @Test
    void should_relocate_button_nodes_under_new_leaves_when_v22_applied() {
        // AC-7（确认④）：30~32→40(sort1~3)、33~35→41(sort1~3)、36~37→42(sort1~2)、38→43(sort1)
        long[][] expected = {
                {30, 40, 1}, {31, 40, 2}, {32, 40, 3},
                {33, 41, 1}, {34, 41, 2}, {35, 41, 3},
                {36, 42, 1}, {37, 42, 2},
                {38, 43, 1},
        };
        for (long[] e : expected) {
            Map<String, Object> row = menuRow(e[0]);
            assertThat(row.get("menu_type")).isEqualTo("BUTTON");
            assertThat(((Number) row.get("parent_id")).longValue()).isEqualTo(e[1]);
            assertThat(((Number) row.get("sort_order")).intValue()).isEqualTo((int) e[2]);
        }
        // 目录 1/8 直下不再有 BUTTON 子节点
        assertThat(count("SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'BUTTON' AND parent_id IN (1, 8)"
                + " AND deleted = false")).isZero();
        // jobs 的 BUTTON 39 仍挂叶子 12，无需动
        assertThat(((Number) menuRow(39).get("parent_id")).longValue()).isEqualTo(12L);
        // role_menu 既有绑定（含 30~38）仍在
        for (long id = 30; id <= 38; id++) {
            assertThat(count("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = " + id)).isPositive();
        }
    }

    @Test
    void should_not_change_button_rows_when_v22_update_replayed() throws Exception {
        // AC-8：旧 parent_id 守卫不命中 → 30~38 行 parent_id/sort_order/version 不再变化
        String cols = "SELECT id, parent_id, sort_order, version FROM sys_menu WHERE id BETWEEN 30 AND 38 ORDER BY id";
        List<Map<String, Object>> before = jdbcTemplate.queryForList(cols);

        replayV22();

        assertThat(jdbcTemplate.queryForList(cols)).isEqualTo(before);
    }
}
