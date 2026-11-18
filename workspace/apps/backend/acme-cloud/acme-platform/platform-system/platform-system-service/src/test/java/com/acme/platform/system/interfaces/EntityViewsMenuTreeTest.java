package com.acme.platform.system.interfaces;

import com.acme.platform.system.api.MenuType;
import com.acme.platform.system.api.dto.AdminMenuView;
import com.acme.platform.system.domain.SysMenu;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单树组装测试（7.2.5：树经 TreeBuilder 组装；可见集过滤 + 父链补全，FR-008 菜单级权限）。
 */
class EntityViewsMenuTreeTest {

    @Test
    void fullTreeBuiltFromFlatList() {
        List<AdminMenuView> tree = EntityViews.menuTree(List.of(
                menu(1L, 0L, "系统管理"),
                menu(2L, 1L, "用户管理"),
                menu(3L, 1L, "角色管理")), null);
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).menuName()).isEqualTo("系统管理");
        assertThat(tree.get(0).children()).extracting(AdminMenuView::menuName)
                .containsExactly("用户管理", "角色管理");
    }

    @Test
    void visibleSetFilteredWithAncestorCompletion() {
        List<AdminMenuView> tree = EntityViews.menuTree(List.of(
                menu(1L, 0L, "系统管理"),
                menu(2L, 1L, "用户管理"),
                menu(3L, 2L, "用户列表")), Set.of(3L));
        // 可见叶子 + 自动补全父链
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).menuName()).isEqualTo("系统管理");
        assertThat(tree.get(0).children()).hasSize(1);
        assertThat(tree.get(0).children().get(0).children()).hasSize(1);
        assertThat(tree.get(0).children().get(0).children().get(0).menuName()).isEqualTo("用户列表");
    }

    @Test
    void buttonLeafAssemblesUnderPageMenuViaAncestorCompletion() {
        // REQ-20261119-051119 确认④（AC-3）：BUTTON 叶子（无 path、无子）经父链补全挂到页面菜单下，
        // 组装路径与 MENU 叶子一致（withAncestors 按 id 走父链，不看 menuType/path）——授权树可见，
        // 导航构建侧（buildDynamicNav）按无 path 剔除，后端树组装无需特判
        List<AdminMenuView> tree = EntityViews.menuTree(List.of(
                menu(1L, 0L, "系统管理"),
                menu(2L, 1L, "用户管理"),
                button(13L, 2L, "创建用户", "system:user:create")), Set.of(13L));
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).menuName()).isEqualTo("系统管理");
        assertThat(tree.get(0).children()).hasSize(1);
        AdminMenuView pageMenu = tree.get(0).children().get(0);
        assertThat(pageMenu.menuName()).isEqualTo("用户管理");
        assertThat(pageMenu.children()).hasSize(1);
        AdminMenuView buttonNode = pageMenu.children().get(0);
        assertThat(buttonNode.menuType()).isEqualTo(MenuType.BUTTON);
        assertThat(buttonNode.path()).isNull();
        assertThat(buttonNode.permissionCode()).isEqualTo("system:user:create");
        assertThat(buttonNode.children()).isEmpty();
    }

    private static SysMenu menu(Long id, Long parentId, String name) {
        SysMenu menu = new SysMenu(parentId, name,
                parentId == 0L ? MenuType.DIRECTORY : MenuType.MENU, null, null, null, 0, true, null);
        return setId(menu, id);
    }

    private static SysMenu button(Long id, Long parentId, String name, String permissionCode) {
        SysMenu menu = new SysMenu(parentId, name, MenuType.BUTTON, null, null, null, 0, true, permissionCode);
        return setId(menu, id);
    }

    private static SysMenu setId(SysMenu menu, Long id) {
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(menu, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return menu;
    }
}
