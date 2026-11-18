package com.acme.platform.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V13 菜单种子 path 对齐 web 路由回归（Testcontainers PG，装配口径同
 * {@code REQ20261114120104TenantIsolationIntegrationTest}；无 Docker 自动跳过）。
 * 背景：V2/V3 种子 path 为后端目录式（/system/users 等），与 web 动态导航契约
 * （叶子 path = 既有路由）不一致，点击菜单落 wildcard 路由回首页。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class)
class MenuPathAlignmentIntegrationTest {

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
    private DataSource dataSource;

    private String pathOf(long id) {
        return jdbcTemplate.queryForObject("SELECT path FROM sys_menu WHERE id = ?", String.class, id);
    }

    @Test
    void should_align_seed_menu_paths_to_web_routes_when_migration_applied() {
        assertThat(pathOf(2)).isEqualTo("/users");
        assertThat(pathOf(3)).isEqualTo("/roles");
        assertThat(pathOf(4)).isEqualTo("/permissions");
        assertThat(pathOf(5)).isEqualTo("/menus");
        assertThat(pathOf(6)).isEqualTo("/dicts");
        assertThat(pathOf(7)).isEqualTo("/tenants");
        assertThat(pathOf(9)).isEqualTo("/audit");
        // 目录不参与导航，path 保持原值
        assertThat(pathOf(1)).isEqualTo("/system");
        assertThat(pathOf(8)).isEqualTo("/infra");
    }

    @Test
    void should_keep_custom_paths_and_not_bump_version_when_migration_rerun() throws Exception {
        jdbcTemplate.update("INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon,"
                + " sort_order, visible, permission_code, status, created_at, created_by, updated_at, updated_by,"
                + " version, deleted) VALUES (900, 1, '自定义入口', 'MENU', '/system/users', NULL, NULL, 99, true,"
                + " NULL, 'ENABLED', now(), 'test', now(), 'test', 0, false)");
        Map<String, Object> before = jdbcTemplate.queryForMap("SELECT path, version FROM sys_menu WHERE id = 2");

        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/migration/system/V13__menu_path_align_web_routes.sql"));
        }

        // 旧 path 但非种子行：人工改值保留；种子行条件不命中 → 不重复 bump version
        assertThat(pathOf(900)).isEqualTo("/system/users");
        assertThat(jdbcTemplate.queryForMap("SELECT path, version FROM sys_menu WHERE id = 2")).isEqualTo(before);
        assertThat(pathOf(1)).isEqualTo("/system");
    }
}