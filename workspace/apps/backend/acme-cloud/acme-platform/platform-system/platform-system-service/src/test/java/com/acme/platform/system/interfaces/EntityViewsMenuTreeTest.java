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

    private static SysMenu menu(Long id, Long parentId, String name) {
        SysMenu menu = new SysMenu(parentId, name,
                parentId == 0L ? MenuType.DIRECTORY : MenuType.MENU, null, null, null, 0, true, null);
        try {
            var field = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(menu, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return menu;
    }
}
