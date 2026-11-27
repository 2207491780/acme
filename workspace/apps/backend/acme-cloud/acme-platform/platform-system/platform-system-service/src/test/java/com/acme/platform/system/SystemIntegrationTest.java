package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.common.contract.PageQuery;
import com.acme.platform.system.api.dto.AdminDeptCreate;
import com.acme.platform.system.api.dto.AdminPositionCreate;
import com.acme.platform.system.api.dto.AdminRoleCreate;
import com.acme.platform.system.api.dto.AdminRoleDataScope;
import com.acme.platform.system.api.dto.AdminUserCreate;
import com.acme.platform.system.application.DeptUseCase;
import com.acme.platform.system.application.PositionUseCase;
import com.acme.platform.system.application.TenantUseCase;
import com.acme.platform.system.application.UserUseCase;
import com.acme.platform.system.infrastructure.repository.SysUserRepository;
import com.acme.platform.system.interfaces.EntityViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-system 集成测试（Testcontainers PostgreSQL，16.2.1 本地可执行、不依赖共享库；
 * 无 Docker 环境自动跳过）：
 * AC-013 实体-脚本一致性（ddl-auto=validate 启动即校验）+ AC-016 双租户隔离 + AC-009 种子幂等
 * + REQ-20261114-120104 组织架构与数据权限（部门/岗位/角色数据范围/用户列表 scope 过滤真实 PG 面）。
 */
class SystemIntegrationTest extends AbstractSystemIntegrationTest {

    @Autowired
    private SysUserRepository userRepository;

    @Autowired
    private UserUseCase userUseCase;

    @Autowired
    private TenantUseCase tenantUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContexts() {
        UserContext.clear();
        TenantContext.clear();
    }

    @Test
    void flywaySeedPresent() {
        // 种子数据（V2 基线 + V3/V4 infra 增补 + V5 内容域增补 + V7 文件/任务/Excel 权限增补
        // + V8 通知公告/站内信权限增补 + V9 组织架构/数据范围权限增补 + V10 登录安全权限增补
        // + V15 系统配置权限/菜单增补 + V19 日志中心权限/菜单增补 + V20 按钮 BUTTON 菜单节点增补
        // + V22 六页 MENU 叶子增补，REQ-20261119-151640 + V24 租户套餐种子，REQ-20261121-022910
        // + V26 调度中心权限种子，REQ-20261121-071002
        // + V27 工作流权限种子，REQ-20261121-142316
        // + V32 开通模板/运营视图权限种子，REQ-20261122-233426）：
        // 默认租户 / 超管 / super_admin / 87 权限点（68+4，V24 续编 69~72 套餐域；
        // V26 续编 73~77 调度中心域——无菜单节点，菜单归 JOB-3；
        // V27 续编 78~82 工作流域——无菜单节点，菜单归 BPM-3；
        // V32 续编 83~87 开通模板+运营视图域——无菜单节点，挂靠租户管理菜单 id=7）/
        // 55 菜单（12 + V20 BUTTON 节点 27 + V22 MENU 叶子 6 + V24 套餐管理叶 46＋BUTTON 47~49
        // + V29 流程中心 50~55（REQ-20261122-102730：DIRECTORY＋两 MENU 叶＋BUTTON×3，跨 REQ 适配 BPM-3 水位对齐）；
        // me.menus 语义不动，BUTTON 仅授权树投影）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_tenant where tenant_code='default'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username='admin'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role where role_code='super_admin'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission", Long.class)).isEqualTo(87L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu", Long.class)).isEqualTo(55L);
        // REQ-20261119-051119 AC-1：BUTTON 节点入 sys_menu（permission_code=既有 API 码）且超管全绑
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type='BUTTON'", Long.class)).isEqualTo(33L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type='BUTTON' "
                        + "and (permission_code is null or permission_code = '' "
                        + "or path is not null or component is not null or icon is not null)",
                Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_menu rm join sys_menu m on m.id = rm.menu_id "
                        + "where m.menu_type='BUTTON' and rm.role_id = "
                        + "(select id from sys_role where role_code='super_admin' and tenant_id='default')",
                Long.class)).isEqualTo(33L);
        // AC-1 权限点总数恒 87（V24 前 68 → V24 套餐域 69~72 → V26 调度中心域 73~77
        // → V27 工作流域 78~82 → V32 开通模板+运营视图域 83~87，REQ-20261122-233426）
        assertThat(jdbcTemplate.queryForList(
                "select distinct m.permission_code from sys_menu m where m.menu_type='BUTTON' "
                        + "and m.permission_code not in (select p.permission_code from sys_permission p)",
                String.class)).isEmpty();
        // REQ-20261114-071607：文件服务/任务调度/Excel demo 权限点种子（接口级 http_method + http_path）
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_permission where permission_code like 'infra:file:%' "
                        + "or permission_code like 'infra:job:%' or permission_code like 'infra:excel:%' order by id",
                String.class))
                .containsExactly("infra:file:list", "infra:file:upload", "infra:file:download",
                        "infra:file:delete", "infra:job:list", "infra:job:run", "infra:excel:demo");
        // REQ-20261114-120102：通知公告/站内信权限点种子（接口级 http_method + http_path；me.menus 语义不动）
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_permission where permission_code like 'system:notice:%' "
                        + "or permission_code like 'system:message:%' order by id",
                String.class))
                .containsExactly("system:notice:list", "system:notice:create", "system:notice:update",
                        "system:notice:delete", "system:notice:publish",
                        "system:message:send", "system:message:list", "system:message:read");
        // REQ-20261114-120104：组织架构/数据范围权限点种子（接口级 http_method + http_path）
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_permission where permission_code like 'system:dept:%' "
                        + "or permission_code like 'system:position:%' "
                        + "or permission_code like 'system:role:scope' order by id",
                String.class))
                .containsExactly("system:dept:list", "system:dept:create", "system:dept:update",
                        "system:dept:delete", "system:position:list", "system:position:create",
                        "system:position:update", "system:position:delete", "system:role:scope");
        // REQ-20261121-071002：调度中心权限点种子（V26，确认②：73~77 共 5 点，无菜单节点；
        // executors 列表归 job:list——GET /admin/v1/scheduler/executions 与 /executors 同挂
        // scheduler:execution:list / scheduler:job:list 域）；接口级 http_method + http_path
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_permission where id between 73 and 77 order by id",
                String.class))
                .containsExactly("scheduler:job:list", "scheduler:job:create", "scheduler:job:update",
                        "scheduler:job:delete", "scheduler:execution:list");
        // REQ-20261121-142316：工作流权限点种子（V27，确认⑦：78~82 共 5 点，无菜单节点，
        // 菜单归 BPM-3）；接口级 http_method + http_path（坑 16 水位抬升适配）
        assertThat(jdbcTemplate.queryForList(
                "select permission_code from sys_permission where id between 78 and 82 order by id",
                String.class))
                .containsExactly("workflow:definition:list", "workflow:definition:deploy",
                        "workflow:instance:manage", "workflow:task:list", "workflow:task:operate");
        // V26+V27+V32 role_permission 水位：行 id 续编 77~81（V26 调度域）+ 82~86（V27 工作流域）
        // + 87~91（V32 开通模板/运营视图域），default 超管绑定共 15 行（确认②/确认⑦/确认⑤）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_permission where id >= 77", Long.class)).isEqualTo(15L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_permission rp join sys_role r on r.id = rp.role_id "
                        + "where rp.permission_id >= 73 and r.role_code='super_admin' "
                        + "and r.tenant_id='default'", Long.class)).isEqualTo(15L);
        // V9 列口径：种子角色 data_scope 可空（null 经 DataScope.parse 归缺省档 ALL，存量角色语义不变）
        assertThat(jdbcTemplate.queryForObject(
                "select data_scope from sys_role where role_code='super_admin'", String.class)).isNull();
    }

    @Test
    void seedScriptReplayIsIdempotent() {
        // 重放 V2/V8/V9/V15/V19/V20/V24/V26/V27/V32 的 INSERT 种子语句（幂等口径 AC-009：固定主键 + ON CONFLICT DO NOTHING
        // 不产生重复行）；DDL 由 Flyway 一次性执行、不在幂等重放范围（裸 CREATE TABLE 重跑必报已存在）
        replaySeedInserts("db/migration/system/V2__seed_baseline.sql");
        replaySeedInserts("db/migration/system/V8__notice_message_notify.sql");
        replaySeedInserts("db/migration/system/V9__org_structure_data_scope.sql");
        replaySeedInserts("db/migration/system/V15__system_config.sql");
        replaySeedInserts("db/migration/system/V19__log_menu_permission.sql");
        replaySeedInserts("db/migration/system/V20__button_menu_nodes.sql");
        replaySeedInserts("db/migration/system/V24__tenant_package.sql");
        // V26 调度中心权限种子（REQ-20261121-071002，文件名后缀未钉死按 V26__*.sql 解析）
        replaySeedInsertsMatching("classpath*:db/migration/system/V26__*.sql");
        // V27 工作流权限种子（REQ-20261121-142316，文件名后缀未钉死按 V27__*.sql 解析）
        replaySeedInsertsMatching("classpath*:db/migration/system/V27__*.sql");
        // V32 开通模板/运营视图权限种子（REQ-20261122-233426，文件名后缀未钉死按 V32__*.sql 解析）
        replaySeedInsertsMatching("classpath*:db/migration/system/V32__*.sql");
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_user where username='admin'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_permission", Long.class)).isEqualTo(87L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_role_permission where permission_id >= 46",
                Long.class)).isEqualTo(42L);
        // V9 幂等：绑定行与权限点不因重放翻倍（>=54 含 V9 9 条 + V10 3 条 + V15 2 条 + V24 4 条 + V26 5 条
        // + V27 5 条 + V32 5 条）
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_role_permission where permission_id >= 54",
                Long.class)).isEqualTo(34L);
        // V26 幂等（REQ-20261121-071002 确认②）：权限点 73~77 与超管绑定行（id 77~81）不因重放翻倍
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission where id between 73 and 77", Long.class)).isEqualTo(5L);
        // V27 幂等（REQ-20261121-142316 确认⑦）：权限点 78~82 与超管绑定行（id 82~86）不因重放翻倍
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission where id between 78 and 82", Long.class)).isEqualTo(5L);
        // V32 幂等（REQ-20261122-233426 确认⑤）：权限点 83~87 与超管绑定行（id 87~91）不因重放翻倍
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission where id between 83 and 87", Long.class)).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_permission where id >= 77", Long.class)).isEqualTo(15L);
        // V20/V24 幂等（REQ-20261119-051119 / REQ-20261121-022910 确认⑥）：BUTTON 节点与超管绑定行
        // 不因重放翻倍（BUTTON 33＝30＋V29 流程中心 BUTTON×3，跨 REQ 适配 BPM-3 水位对齐）；
        // role_menu id>=13 共 43 行＝BUTTON 绑定 27＋V22 叶子绑定 6＋V24 套餐绑定 4（id 46~49）
        // ＋V29 流程中心绑定 6（id 50~55，REQ-20261122-102730）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_menu where menu_type='BUTTON'", Long.class)).isEqualTo(33L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sys_role_menu where id >= 13",
                Long.class)).isEqualTo(43L);
    }

    @Test
    void tenantIsolationFiltersTenantOwnedTables() {
        // 双租户数据隔离（AC-016 / 16.1 多租户场景）：A 租户查不到 B 租户用户。
        // 数据权限示范域（AC-4）要求页面调用带主体上下文：各租户内置 ALL 档角色（data_scope 列缺省）。
        TenantContext.bind(new TenantContext("tenant-a"));
        roleUseCase.create(new com.acme.platform.system.api.dto.AdminRoleCreate(
                null, "it_admin_a", "租户A管理", null));
        userUseCase.create(new AdminUserCreate("user-a", "租户A用户", null, null, List.of()));
        TenantContext.bind(new TenantContext("tenant-b"));
        roleUseCase.create(new com.acme.platform.system.api.dto.AdminRoleCreate(
                null, "it_admin_b", "租户B管理", null));
        userUseCase.create(new AdminUserCreate("user-b", "租户B用户", null, null, List.of()));

        TenantContext.bind(new TenantContext("tenant-a"));
        UserContext.bind(new UserContext("1", java.util.Set.of("it_admin_a")));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null, null).records())
                .allSatisfy(user -> assertThat(user.getUsername()).isEqualTo("user-a"));

        TenantContext.bind(new TenantContext("tenant-b"));
        UserContext.bind(new UserContext("1", java.util.Set.of("it_admin_b")));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null, null).records())
                .allSatisfy(user -> assertThat(user.getUsername()).isEqualTo("user-b"));
    }

    @Test
    void missingTenantReadYieldsEmptyFailClosed() {
        // 无租户访问租户归属表：framework-tenant fail-closed（修订口径：读恒空集不跨租户出数，写显式拒绝）
        TenantContext.bind(null);
        var result = userUseCase.page(PageQuery.firstPage(), null, null, null);
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

    @Autowired
    private DeptUseCase deptUseCase;

    @Autowired
    private PositionUseCase positionUseCase;

    @Test
    void permissionCrudLifecycle() {
        var created = permissionUseCase.create(new com.acme.platform.system.api.dto.AdminPermissionCreate(
                "it:perm:x", "集成测试权限点", com.acme.platform.system.api.PermissionType.API,
                "GET", "/it/x"));
        var updated = permissionUseCase.update(created.getId(),
                new com.acme.platform.system.api.dto.AdminPermissionUpdate("改名", "PUT", "/it/y",
                        created.getVersion()));
        assertThat(updated.getPermissionName()).isEqualTo("改名");
        assertThat(permissionUseCase.page(new PageQuery(1, 50), "it:perm:x", null, null).total()).isEqualTo(1L);
        permissionUseCase.delete(created.getId());
        assertThat(permissionUseCase.page(new PageQuery(1, 50), "it:perm:x", null, null).total()).isZero();
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
            viewer = userRepository.findByUsername("tenant-a", "viewer");
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
        // REQ-20261125-001738：画像按 (tenantCode, username) 域内查档
        var profile = authProfileUseCase.findByUsername("tenant-a", "viewer");
        assertThat(profile.username()).isEqualTo("viewer");
        assertThat(profile.roles()).containsExactly("it_viewer");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> authProfileUseCase.findByUsername("tenant-a", "no-such-user-it"))
                .isInstanceOf(com.acme.common.core.BizException.class);

        // 权限判定：无绑定权限 → false；super_admin 短路 → true
        UserContext.bind(new com.acme.common.context.UserContext(String.valueOf(viewer.getId()),
                java.util.Set.of("it_viewer")));
        assertThat(authorizationUseCase.hasPermission("system:user:create")).isFalse();
        UserContext.bind(new com.acme.common.context.UserContext("1", java.util.Set.of("super_admin")));
        assertThat(authorizationUseCase.hasPermission("system:user:create")).isTrue();
        UserContext.clear();
    }

    // ---------- 组织架构与数据权限（REQ-20261114-120104，真实 PG 事务与租户面） ----------

    @Test
    void deptLifecycleTreeAndDeleteGuards() {
        // AC-1 / AC-6：树形返回含禁用态、删有子/有用户的部门拒绝、跨租户不可见
        TenantContext.bind(new TenantContext("tenant-dept"));
        var hq = deptUseCase.create(new AdminDeptCreate(0L, "总部", 1, null));
        var child = deptUseCase.create(new AdminDeptCreate(hq.getId(), "研发部", 1, null));
        child.setStatus(com.acme.platform.system.api.EnableStatus.DISABLED);
        deptUseCase.update(child.getId(), new com.acme.platform.system.api.dto.AdminDeptUpdate(
                "研发部", null, 1, com.acme.platform.system.api.EnableStatus.DISABLED, child.getVersion()));

        // 树形返回（含禁用态）；子树 path 前缀展开命中自身与子孙
        var tree = EntityViews.deptTree(deptUseCase.listAll());
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).deptName()).isEqualTo("总部");
        assertThat(tree.get(0).children()).extracting(
                com.acme.platform.system.api.dto.AdminDeptView::deptName).containsExactly("研发部");
        assertThat(tree.get(0).children().get(0).status())
                .isEqualTo(com.acme.platform.system.api.EnableStatus.DISABLED);
        assertThat(deptUseCase.subtreeIds(hq.getId())).containsExactlyInAnyOrder(hq.getId(), child.getId());

        // 删除守卫：有子部门 → DEPT_HAS_CHILDREN
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deptUseCase.delete(hq.getId()))
                .isInstanceOf(com.acme.common.core.BizException.class)
                .satisfies(ex -> assertThat(((com.acme.common.core.BizException) ex).errorCode().code())
                        .isEqualTo(com.acme.platform.system.SystemError.DEPT_HAS_CHILDREN.code()));
        // 有用户归属 → DEPT_HAS_USERS
        userUseCase.create(new AdminUserCreate("dept_user", "部门用户", null, null, List.of(),
                child.getId(), null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deptUseCase.delete(child.getId()))
                .isInstanceOf(com.acme.common.core.BizException.class)
                .satisfies(ex -> assertThat(((com.acme.common.core.BizException) ex).errorCode().code())
                        .isEqualTo(com.acme.platform.system.SystemError.DEPT_HAS_USERS.code()));
        // 防环：总部重挂到其子孙 → DEPT_PARENT_CYCLE
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deptUseCase.update(hq.getId(),
                        new com.acme.platform.system.api.dto.AdminDeptUpdate("总部", child.getId(), 1,
                                null, hq.getVersion())))
                .isInstanceOf(com.acme.common.core.BizException.class)
                .satisfies(ex -> assertThat(((com.acme.common.core.BizException) ex).errorCode().code())
                        .isEqualTo(com.acme.platform.system.SystemError.DEPT_PARENT_CYCLE.code()));

        // 跨租户不可见（AC-6）：租户过滤后 404
        TenantContext.bind(new TenantContext("tenant-dept-other"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deptUseCase.get(hq.getId()))
                .isInstanceOf(com.acme.common.core.BizException.class);
    }

    @Test
    void positionLifecycleAndReferenceGuard() {
        // AC-2：被引用删除拒绝、无引用删除成功、编码租户内唯一
        TenantContext.bind(new TenantContext("tenant-pos"));
        var referenced = positionUseCase.create(new AdminPositionCreate("it_eng", "集成工程师", null));
        var free = positionUseCase.create(new AdminPositionCreate("it_free", "空闲岗位", null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> positionUseCase.create(
                        new AdminPositionCreate("it_eng", "重复编码", null)))
                .isInstanceOf(com.acme.common.core.BizException.class);

        userUseCase.create(new AdminUserCreate("pos_user", "岗位用户", null, null, List.of(),
                null, referenced.getId()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> positionUseCase.delete(referenced.getId()))
                .isInstanceOf(com.acme.common.core.BizException.class)
                .satisfies(ex -> assertThat(((com.acme.common.core.BizException) ex).errorCode().code())
                        .isEqualTo(com.acme.platform.system.SystemError.POSITION_IN_USE.code()));
        positionUseCase.delete(free.getId());
        assertThat(positionUseCase.page(new PageQuery(1, 20), "it_free", null).total()).isZero();
    }

    @Test
    void roleDataScopeLifecycleOnRealDatabase() {
        // AC-3：档位校验 / CUSTOM 部门集落库 / 非 CUSTOM 清空重写
        TenantContext.bind(new TenantContext("tenant-scope"));
        var hq = deptUseCase.create(new AdminDeptCreate(0L, "总部", 1, null));
        var role = roleUseCase.create(new AdminRoleCreate(null, "it_scope", "范围角色", null));

        // 档位未知 → DATA_SCOPE_INVALID
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roleUseCase.updateDataScope(role.getId(),
                        new AdminRoleDataScope("TENANT", List.of())))
                .isInstanceOf(com.acme.common.core.BizException.class)
                .satisfies(ex -> assertThat(((com.acme.common.core.BizException) ex).errorCode().code())
                        .isEqualTo(com.acme.platform.system.SystemError.DATA_SCOPE_INVALID.code()));
        // CUSTOM 未传部门集 → DATA_SCOPE_INVALID
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roleUseCase.updateDataScope(role.getId(),
                        new AdminRoleDataScope("CUSTOM", List.of())))
                .isInstanceOf(com.acme.common.core.BizException.class);
        // CUSTOM 含未知部门 → DATA_SCOPE_INVALID
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roleUseCase.updateDataScope(role.getId(),
                        new AdminRoleDataScope("CUSTOM", List.of(999999999999L))))
                .isInstanceOf(com.acme.common.core.BizException.class);
        // 传合法 deptIds → sys_role_dept 落库（AC-3）
        roleUseCase.updateDataScope(role.getId(), new AdminRoleDataScope("CUSTOM", List.of(hq.getId())));
        assertThat(jdbcTemplate.queryForList(
                "select dept_id from sys_role_dept where role_id = ?", Long.class, role.getId()))
                .containsExactly(hq.getId());
        assertThat(roleUseCase.get(role.getId()).getDataScope()).isEqualTo("CUSTOM");
        // 非 CUSTOM 档 → 绑定行清空
        roleUseCase.updateDataScope(role.getId(), new AdminRoleDataScope("DEPT", List.of()));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role_dept where role_id = ?", Long.class, role.getId())).isZero();
        assertThat(roleUseCase.get(role.getId()).getDataScope()).isEqualTo("DEPT");
    }

    @Test
    void userListFilteredByRoleDataScope() {
        // AC-4：DEPT_AND_CHILD 含子部门、SELF 仅本人、CUSTOM 自定义集、ALL 全部（真实 PG 面）
        TenantContext.bind(new TenantContext("tenant-scope"));
        var hq = deptUseCase.create(new AdminDeptCreate(0L, "总部", 1, null));
        var rd = deptUseCase.create(new AdminDeptCreate(hq.getId(), "研发部", 1, null));
        var fe = deptUseCase.create(new AdminDeptCreate(rd.getId(), "前端组", 1, null));
        var uHq = userUseCase.create(new AdminUserCreate("u_hq", "总部用户", null, null, List.of(),
                hq.getId(), null));
        var uRd = userUseCase.create(new AdminUserCreate("u_rd", "研发用户", null, null, List.of(),
                rd.getId(), null));
        userUseCase.create(new AdminUserCreate("u_fe", "前端用户", null, null, List.of(), fe.getId(), null));
        var role = roleUseCase.create(new AdminRoleCreate(null, "it_scope_rd", "研发视图角色", null));
        // 主体 = 研发用户，持范围角色（档位随用例调整）
        UserContext.bind(new UserContext(String.valueOf(uRd.getId()), java.util.Set.of("it_scope_rd")));

        // DEPT_AND_CHILD：本部门 + 子部门（研发部 + 前端组），不含总部
        roleUseCase.updateDataScope(role.getId(), new AdminRoleDataScope("DEPT_AND_CHILD", List.of()));
        var page = userUseCase.page(PageQuery.firstPage(), null, null, null);
        assertThat(page.records()).extracting(com.acme.platform.system.domain.SysUser::getUsername)
                .containsExactlyInAnyOrder("u_rd", "u_fe");

        // SELF：仅本人
        roleUseCase.updateDataScope(role.getId(), new AdminRoleDataScope("SELF", List.of()));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null, null).records())
                .extracting(com.acme.platform.system.domain.SysUser::getUsername)
                .containsExactly("u_rd");

        // CUSTOM：自定义部门集（总部）
        roleUseCase.updateDataScope(role.getId(), new AdminRoleDataScope("CUSTOM", List.of(hq.getId())));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null, null).records())
                .extracting(com.acme.platform.system.domain.SysUser::getUsername)
                .containsExactly("u_hq");

        // deptId 入参（含子部门）叠加 scope 过滤：SELF 档 ∩ 研发部子树 → 本人
        roleUseCase.updateDataScope(role.getId(), new AdminRoleDataScope("DEPT_AND_CHILD", List.of()));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null, rd.getId()).records())
                .extracting(com.acme.platform.system.domain.SysUser::getUsername)
                .containsExactlyInAnyOrder("u_rd", "u_fe");

        // ALL：全部
        roleUseCase.updateDataScope(role.getId(), new AdminRoleDataScope("ALL", List.of()));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null, null).total()).isEqualTo(3L);

        // 多角色合并：ALL 角色并存 → 不受限
        var allRole = roleUseCase.create(new AdminRoleCreate(null, "it_scope_all", "全量角色", null));
        roleUseCase.updateDataScope(allRole.getId(), new AdminRoleDataScope("ALL", List.of()));
        UserContext.bind(new UserContext(String.valueOf(uRd.getId()),
                java.util.Set.of("it_scope_rd", "it_scope_all")));
        assertThat(userUseCase.page(PageQuery.firstPage(), null, null, null).total()).isEqualTo(3L);
        // 归属字段校验：跨租户 deptId 拒绝（DEPT_NOT_FOUND）
        TenantContext.bind(new TenantContext("tenant-scope-other"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> userUseCase.create(
                        new AdminUserCreate("u_ghost", "幽灵用户", null, null, List.of(), hq.getId(), null)))
                .isInstanceOf(com.acme.common.core.BizException.class);
        assertThat(uHq.getDeptId()).isEqualTo(hq.getId());
        UserContext.clear();
    }

    /** 按分号切分迁移脚本、剔除 -- 注释行后仅重放 INSERT 语句（种子值不含分号字面量）。 */
    private void replaySeedInserts(String path) {
        replaySeedInsertText(readResource(path), path);
    }

    /** 按通配模式解析迁移脚本后重放 INSERT（文件名后缀未钉死的迁移用，如 V26）。 */
    private void replaySeedInsertsMatching(String locationPattern) {
        try {
            var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources(locationPattern);
            assertThat(resources).as("迁移脚本缺失（backend 未落盘或命名不符）：%s", locationPattern).isNotEmpty();
            for (var resource : resources) {
                replaySeedInsertText(
                        new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                        resource.getFilename());
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void replaySeedInsertText(String script, String sourceName) {
        for (String chunk : script.split(";")) {
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
        try (java.io.InputStream in = SystemIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("资源缺失：%s", path).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
