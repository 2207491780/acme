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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261114-120105 V10 权限点种子冒烟（platform-system；AC-6 权限判定的数据前置）：
 * auth:loginlog:list / auth:online:list / auth:online:revoke 三码存在且绑定 super_admin
 * （照抄 {@code REQ20261114120104DeptApiIntegrationTest} V9 种子冒烟口径：存在性 containsExactly +
 * 超管全量绑定 NOT EXISTS）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=req120105-seed-secret-0123456789abcd"
})
class REQ20261114120105AuthPermissionSeedIntegrationTest {

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

    @Test
    void flywaySeedsAuthSecurityPermissionCodesAndBindsSuperAdmin() {
        // 契约变更清单：权限点种子 3 条（V10）
        List<String> codes = jdbcTemplate.queryForList(
                "select permission_code from sys_permission where permission_code in ("
                        + "'auth:loginlog:list','auth:online:list','auth:online:revoke') order by id",
                String.class);
        assertThat(codes).containsExactly("auth:loginlog:list", "auth:online:list", "auth:online:revoke");

        // V9 先例（种子幂等 + 超管全量绑定）：3 码均须绑定 super_admin
        Long unboundCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_permission p "
                        + "where p.permission_code in ("
                        + "'auth:loginlog:list','auth:online:list','auth:online:revoke') "
                        + "and not exists (select 1 from sys_role_permission rp where rp.permission_id = p.id "
                        + "and rp.role_id = (select id from sys_role where role_code='super_admin' and tenant_id='default'))",
                Long.class);
        assertThat(unboundCount).as("V10 新权限点应全部绑定 super_admin").isZero();
    }
}
