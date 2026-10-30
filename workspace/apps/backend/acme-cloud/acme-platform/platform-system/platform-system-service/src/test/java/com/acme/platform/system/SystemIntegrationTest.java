package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.application.TenantUseCase;
import com.acme.platform.system.application.UserUseCase;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.interfaces.EntityViews;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-system 集成测试（Testcontainers PostgreSQL，16.2.1 本地可执行、不依赖共享库；
 * 无 Docker 环境自动跳过）：
 * AC-013 实体-脚本一致性（ddl-auto=validate 启动即校验）+ AC-016 双租户隔离 + AC-009 种子幂等。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PlatformSystemApplication.class, properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "acme.security.token.hmac-secret=system-integration-test-secret-0123456789"
})
class SystemIntegrationTest {

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
    private SysUserRepository userRepository;

    @Autowired
    private UserUseCase userUseCase;

    @Autowired
    private TenantUseCase tenantUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void flywaySeedPresent() {
        // 种子数据（V2 基线 + V3/V4 infra 增补 + V5 内容域增补）：默认租户 / 超管 / super_admin / 38 权限点 / 9 菜单（AC-009 前置）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant where tenant_code='default'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username='admin'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role where role_code='super_admin'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission", Long.class)).isEqualTo(38L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu", Long.class)).isEqualTo(9L);
    }

    @Test
    void seedScriptReplayIsIdempotent() {
        // 直接重放 V2 种子 SQL（幂等口径 AC-009：固定主键 + ON CONFLICT DO NOTHING 不产生重复行）
        jdbcTemplate.execute(readResource("db/migration/V2__seed_baseline.sql"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_user where username='admin'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class)).isEqualTo(38L);
    }

    @Test
    void tenantIsolationFiltersTenantOwnedTables() {
        // 双租户数据隔离（AC-016 / 16.1 多租户场景）：A 租户查不到 B 租户用户
        TenantContext.bind(new TenantContext("tenant-a"));
        userUseCase.create(new AdminUserCreate("user-a", "租户A用户", null, null, List.of()));
        TenantContext.bind(new TenantContext("tenant-b"));
        userUseCase.create(new AdminUserCreate("user-b", "租户B用户", null, null, List.of()));

        TenantContext.bind(new TenantContext("tenant-a"));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null).records())
                .allSatisfy(user -> assertThat(user.getUsername()).isEqualTo("user-a"));

        TenantContext.bind(new TenantContext("tenant-b"));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null).records())
                .allSatisfy(user -> assertThat(user.getUsername()).isEqualTo("user-b"));
    }

    @Test
    void missingTenantReadYieldsEmptyFailClosed() {
        // 无租户访问租户归属表：framework-tenant fail-closed（修订口径：读恒空集不跨租户出数，写显式拒绝）
        TenantContext.bind(null);
        var result = userUseCase.page(PageQuery.firstPage(), null, null);
        org.assertj.core.api.Assertions.assertThat(result.records()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(result.total()).isZero();
    }

    @Test
    void tenantListBypassesTenantFilterForPlatformManagement() {
        // 平台管理跨租户（12.4.2）：租户目录列表在任意租户上下文（或无）可见
        TenantContext.bind(new TenantContext("tenant-a"));
        assertThat(tenantUseCase.page(PageQuery.firstPage(), null).total()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void seedSuperadminStatusEnabled() {
        Map<String, Object> admin = jdbcTemplate.queryForMap(
                "select status, built_in, tenant_id from sys_user where username='admin'");
        assertThat(admin.get("status")).isEqualTo("ENABLED");
        assertThat(admin.get("built_in")).isEqualTo(Boolean.TRUE);
        assertThat(admin.get("tenant_id")).isEqualTo("default");
    }

    // ---------- 六能力管理闭环（FR-006~008，真实 PG 事务与持久化面） ----------

    @Autowired
    private com.acme.platform.system.application.RoleUseCase roleUseCase;

    @Autowired
    private com.acme.platform.system.application.PermissionUseCase permissionUseCase;

    @Autowired
    private com.acme.platform.system.application.MenuUseCase menuUseCase;

    @Autowired
    private com.acme.platform.system.application.DictUseCase dictUseCase;

    @Autowired
    private com.acme.platform.system.application.MeUseCase meUseCase;

    @Autowired
    private com.acme.platform.system.application.AuthProfileUseCase authProfileUseCase;

    @Autowired
    private com.acme.platform.system.application.AuthorizationUseCase authorizationUseCase;

    @Test
    void permissionCrudLifecycle() {
        var created = permissionUseCase.create(new com.acme.platform.system.api.dto.AdminPermissionCreate(
                "it:perm:x", "集成测试权限点", com.acme.platform.system.api.PermissionType.API,
                "GET", "/it/x"));
        var updated = permissionUseCase.update(created.getId(),
                new com.acme.platform.system.api.dto.AdminPermissionUpdate("改名", "PUT", "/it/y",
                        created.getVersion()));
        assertThat(updated.getPermissionName()).isEqualTo("改名");
        assertThat(permissionUseCase.page(new PageQuery(1, 50), "it:perm:x").total()).isEqualTo(1L);
        permissionUseCase.delete(created.getId());
        assertThat(permissionUseCase.page(new PageQuery(1, 50), "it:perm:x").total()).isZero();
    }

    @Test
    void menuAndDictLifecycle() {
        // 字典为租户归属实体（@TenantId 写入拒绝在未绑定会话触发）——统一绑定租户上下文
        TenantContext.bind(new TenantContext("tenant-a"));
        // 菜单：创建 → 树含新节点 → 更新 → 子节点存在时删除被拒
        var directory = menuUseCase.create(new com.acme.platform.system.api.dto.AdminMenuCreate(
                0L, "IT 目录", com.acme.platform.system.api.MenuType.DIRECTORY,
                "/it", null, null, 99, true, null));
        var leaf = menuUseCase.create(new com.acme.platform.system.api.dto.AdminMenuCreate(
                directory.getId(), "IT 页面", com.acme.platform.system.api.MenuType.MENU,
                "/it/page", null, null, 1, true, null));
        assertThat(EntityViews.menuTree(menuUseCase.listAll(), null)).extracting(
                com.acme.platform.system.api.dto.AdminMenuView::menuName).contains("IT 目录");
        var updated = menuUseCase.update(leaf.getId(), new com.acme.platform.system.api.dto.AdminMenuUpdate(
                "IT 页面二", null, null, null, 2, true, null, leaf.getVersion()));
        assertThat(updated.getMenuName()).isEqualTo("IT 页面二");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> menuUseCase.delete(directory.getId()))
                .isInstanceOf(com.acme.common.core.BizException.class);

        // 字典：类型 + 项 + 同值冲突 + 删除约束
        var dictType = dictUseCase.createType(new com.acme.platform.system.api.dto.AdminDictTypeCreate(
                "it_dict", "集成字典", null));
        dictUseCase.createItem(dictType.getId(), new com.acme.platform.system.api.dto.AdminDictItemCreate(
                "标签", "V1", 0));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dictUseCase.createItem(dictType.getId(),
                        new com.acme.platform.system.api.dto.AdminDictItemCreate("重复", "V1", 1)))
                .isInstanceOf(com.acme.common.core.BizException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dictUseCase.deleteType(dictType.getId()))
                .isInstanceOf(com.acme.common.core.BizException.class);
        assertThat(dictUseCase.listItems(dictType.getId())).hasSize(1);
    }

    @Test
    void roleBindPermissionsAndMenusOnRealDatabase() {
        TenantContext.bind(new TenantContext("tenant-a"));
        var role = roleUseCase.create(new com.acme.platform.system.api.dto.AdminRoleCreate(
                null, "it_ops", "集成运维", null));
        var permission = permissionUseCase.create(new com.acme.platform.system.api.dto.AdminPermissionCreate(
                "it:ops:do", "集成动作", com.acme.platform.system.api.PermissionType.API, "PUT", "/it/ops"));
        var menu = menuUseCase.create(new com.acme.platform.system.api.dto.AdminMenuCreate(
                0L, "IT 菜单", com.acme.platform.system.api.MenuType.MENU, "/it-menu", null, null, 98,
                true, null));

        roleUseCase.bindPermissions(role.getId(), List.of(permission.getId()));
        assertThat(roleUseCase.permissionIdsOf(role.getId())).containsExactly(permission.getId());
        // 整体替换幂等（10.3.1）
        roleUseCase.bindPermissions(role.getId(), List.of(permission.getId()));
        assertThat(roleUseCase.permissionIdsOf(role.getId())).containsExactly(permission.getId());

        roleUseCase.bindMenus(role.getId(), List.of(menu.getId()));
        assertThat(roleUseCase.menuIdsOf(role.getId())).contains(menu.getId());

        // 角色父子树（role-tree）：子角色挂到新建角色下，树组装嵌套；存在子角色拒绝删除
        var childRole = roleUseCase.create(new com.acme.platform.system.api.dto.AdminRoleCreate(
                role.getId(), "it_ops_child", "集成运维子角色", null));
        var roleTree = EntityViews.roleTree(roleUseCase.listAll());
        assertThat(roleTree).anyMatch(root -> root.roleCode().equals("it_ops")
                && root.children().stream().anyMatch(c -> c.roleCode().equals("it_ops_child")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roleUseCase.delete(role.getId()))
                .isInstanceOf(com.acme.common.core.BizException.class);
        roleUseCase.delete(childRole.getId());

        // 权限点 / 菜单绑定不阻断删除（5.1：仅内置角色 / 存在子角色 / 存在用户绑定拒绝）——删除时解绑（7.2.7 物理删）
        roleUseCase.delete(role.getId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roleUseCase.get(role.getId()))
                .isInstanceOf(com.acme.common.core.BizException.class);
        assertThat(roleUseCase.permissionIdsOf(role.getId())).isEmpty();
    }

    @Test
    void meViewAndProfileAndAuthorizationFlows() {
        // 前置：tenant-a 建用户（复用隔离用例数据，存在则复用）
        TenantContext.bind(new TenantContext("tenant-a"));
        com.acme.platform.system.domain.SysUser viewer;
        try {
            viewer = userUseCase.create(new AdminUserCreate("viewer", "视图用户", null, null, List.of()));
        } catch (com.acme.common.core.BizException duplicated) {
            viewer = userRepository.findByUsername("viewer");
        }
        var role = roleUseCase.create(new com.acme.platform.system.api.dto.AdminRoleCreate(
                null, "it_viewer", "视图角色", null));
        userUseCase.bindRoles(viewer.getId(), List.of(role.getId()));

        // /me 视图（roles 取自上下文 claims，菜单按角色绑定过滤）
        UserContext.bind(new com.acme.common.context.UserContext(String.valueOf(viewer.getId()),
                java.util.Set.of("it_viewer")));
        var snapshot = meUseCase.loadMe();
        assertThat(snapshot.user().getUsername()).isEqualTo("viewer");
        assertThat(snapshot.roles()).containsExactly("it_viewer");

        // 登录画像（@IgnoreTenant 平台级）：found 与 not-found
        var profile = authProfileUseCase.findByUsername("viewer");
        assertThat(profile.username()).isEqualTo("viewer");
        assertThat(profile.roles()).containsExactly("it_viewer");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> authProfileUseCase.findByUsername("no-such-user-it"))
                .isInstanceOf(com.acme.common.core.BizException.class);

        // 权限判定：无绑定权限 → false；super_admin 短路 → true
        UserContext.bind(new com.acme.common.context.UserContext(String.valueOf(viewer.getId()),
                java.util.Set.of("it_viewer")));
        assertThat(authorizationUseCase.hasPermission("system:user:create")).isFalse();
        UserContext.bind(new com.acme.common.context.UserContext("1", java.util.Set.of("super_admin")));
        assertThat(authorizationUseCase.hasPermission("system:user:create")).isTrue();
        UserContext.clear();
    }

    private static String readResource(String path) {
        try (java.io.InputStream in = SystemIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("资源缺失：%s", path).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}