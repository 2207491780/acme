package com.acme.platform.system.interfaces;

import com.acme.platform.system.api.dto.AdminRoleView;
import com.acme.platform.system.domain.SysRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色树组装测试（角色父子关系树状定义：根哨兵 0，树经 TreeBuilder 组装，环 / 重复 id / 孤儿显式失败）。
 */
class EntityViewsRoleTreeTest {

    @Test
    void fullTreeBuiltFromFlatList() {
        List<AdminRoleView> tree = EntityViews.roleTree(List.of(
                role(1L, 0L, "super_admin"),
                role(3L, 1L, "dev"),
                role(2L, 1L, "ops")));
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).roleCode()).isEqualTo("super_admin");
        // 子角色按入参顺序（用例层按 id 升序出库）嵌套
        assertThat(tree.get(0).children()).extracting(AdminRoleView::roleCode)
                .containsExactly("dev", "ops");
        assertThat(tree.get(0).children().get(0).children()).isEmpty();
    }

    @Test
    void flatViewKeepsEmptyChildrenAndRootSentinel() {
        AdminRoleView view = EntityViews.roleView(role(1L, 0L, "ops"));
        assertThat(view.parentId()).isEqualTo(0L);
        assertThat(view.children()).isEmpty();
    }

    @Test
    void emptyListBuildsEmptyTree() {
        assertThat(EntityViews.roleTree(List.of())).isEmpty();
    }

    private static SysRole role(Long id, Long parentId, String code) {
        SysRole role = new SysRole(parentId, code, code, null, false);
        try {
            var field = com.acme.framework.jpa.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(role, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return role;
    }
}
