package com.acme.platform.system;

import com.acme.common.context.TenantContext;
import com.acme.common.context.UserContext;
import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.dto.AdminMenuView;
import com.acme.platform.system.api.dto.AdminRoleCreate;
import com.acme.platform.system.application.MeUseCase;
import com.acme.platform.system.application.MenuUseCase;
import com.acme.platform.system.application.RoleUseCase;
import com.acme.platform.system.interfaces.EntityViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261119-051119 BUTTON 菜单绑定联动 role_permission 全链集成测试（Testcontainers PG，
 * 装配口径照 REQ20261117025200SwitchSessionMeIntegrationTest / SystemIntegrationTest；
 * 无 Docker 自动跳过）。
 *
 * <p>覆盖 AC-2（勾选 BUTTON → role_permission 出现对应权限点；取消 → 消失；非按钮来源直绑不动）
 * 与 AC-3（/me 菜单树含 BUTTON 节点、permissions 集合随联动变化）。</p>
 *
 * <p>种子锚点（V2/V20）：BUTTON 13=创建用户（码 system:user:create → 权限点 3）、
 * BUTTON 15=删除用户（码 system:user:delete → 权限点 6）、页面菜单 2=用户管理（父 1=系统管理目录）、
 * 权限点 67=system:config:update（无 BUTTON 投影，作非按钮来源直绑锚点）。</p>
 */
class REQ20261119051119ButtonMenuBindIntegrationTest extends AbstractSystemIntegrationTest {

    private static final String TENANT = "req051119-t1";
    private static final String ROLE_CODE = "btn_ops";

    /** V20 种子锚点（见类注）。 */
    private static final long MENU_USERS = 2L;
    private static final long BTN_CREATE_USER = 13L;
    private static final long BTN_DELETE_USER = 15L;
    private static final long PERM_CREATE_USER = 3L;
    private static final long PERM_DELETE_USER = 6L;
    private static final long PERM_CONFIG_UPDATE = 67L;
    private static final String CODE_CREATE_USER = "system:user:create";
    private static final String CODE_DELETE_USER = "system:user:delete";
    private static final String CODE_CONFIG_UPDATE = "system:config:update";

    @Autowired
    private RoleUseCase roleUseCase;

    @Autowired
    private MenuUseCase menuUseCase;

    @Autowired
    private MeUseCase meUseCase;

    @AfterEach
    void clearContexts() {
        UserContext.clear();
        TenantContext.clear();
    }

    @Test
    void should_syncButtonPermissionSubsetOnBindMenus_andKeepNonButtonBindings() {
        TenantContext.bind(new TenantContext(TENANT));
        var role = roleUseCase.create(new AdminRoleCreate(null, ROLE_CODE, "按钮联动角色", null));
        // 非按钮来源直绑（权限点勾选面）：任何 bindMenus 联动都不得触碰
        roleUseCase.bindPermissions(role.getId(), List.of(PERM_CONFIG_UPDATE));

        // ---- 勾选：页面菜单 2 + BUTTON 13 → role_menu 替换（父链补全 1/2/13）+ role_permission 出现 3 ----
        roleUseCase.bindMenus(role.getId(), List.of(MENU_USERS, BTN_CREATE_USER));
        assertThat(roleUseCase.menuIdsOf(role.getId()))
                .containsExactlyInAnyOrder(1L, MENU_USERS, BTN_CREATE_USER);
        assertThat(roleUseCase.permissionIdsOf(role.getId()))
                .containsExactlyInAnyOrder(PERM_CONFIG_UPDATE, PERM_CREATE_USER);

        // AC-3：/me 菜单树含 BUTTON 节点（授权树可见）、permissions 集合随联动出现按钮码
        UserContext.bind(new UserContext("1", Set.of(ROLE_CODE)));
        var bound = meUseCase.loadMe();
        assertThat(bound.permissions()).contains(CODE_CREATE_USER, CODE_CONFIG_UPDATE)
                .doesNotContain(CODE_DELETE_USER);
        AdminMenuView buttonNode = findNode(EntityViews.menuTree(menuUseCase.listAll(), bound.menuIds()),
                BTN_CREATE_USER);
        assertThat(buttonNode).as("勾选后 /me 菜单树含 BUTTON 节点").isNotNull();
        assertThat(buttonNode.menuType()).isEqualTo(MenuType.BUTTON);
        assertThat(buttonNode.path()).as("BUTTON 节点无 path（导航构建剔除）").isNull();
        assertThat(buttonNode.children()).isEmpty();
        assertThat(buttonNode.permissionCode()).isEqualTo(CODE_CREATE_USER);
        UserContext.clear();

        // ---- 混合切换：13 → 15 → role_permission 差量（删 3 增 6），非按钮直绑 67 不动 ----
        roleUseCase.bindMenus(role.getId(), List.of(MENU_USERS, BTN_DELETE_USER));
        assertThat(roleUseCase.menuIdsOf(role.getId()))
                .containsExactlyInAnyOrder(1L, MENU_USERS, BTN_DELETE_USER);
        assertThat(roleUseCase.permissionIdsOf(role.getId()))
                .containsExactlyInAnyOrder(PERM_CONFIG_UPDATE, PERM_DELETE_USER);
        UserContext.bind(new UserContext("1", Set.of(ROLE_CODE)));
        assertThat(meUseCase.loadMe().permissions())
                .contains(CODE_DELETE_USER, CODE_CONFIG_UPDATE).doesNotContain(CODE_CREATE_USER);
        UserContext.clear();

        // ---- 全取消 BUTTON：按钮来源绑定清空，仅存非按钮直绑 ----
        roleUseCase.bindMenus(role.getId(), List.of(MENU_USERS));
        assertThat(roleUseCase.menuIdsOf(role.getId())).containsExactlyInAnyOrder(1L, MENU_USERS);
        assertThat(roleUseCase.permissionIdsOf(role.getId())).containsExactly(PERM_CONFIG_UPDATE);
        UserContext.bind(new UserContext("1", Set.of(ROLE_CODE)));
        assertThat(meUseCase.loadMe().permissions()).containsExactly(CODE_CONFIG_UPDATE);
    }

    /** 树中按 id 找节点（/me menuTree 断言用）。 */
    private static AdminMenuView findNode(List<AdminMenuView> tree, long id) {
        for (AdminMenuView node : tree) {
            if (node.id() == id) {
                return node;
            }
            AdminMenuView found = findNode(node.children(), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
