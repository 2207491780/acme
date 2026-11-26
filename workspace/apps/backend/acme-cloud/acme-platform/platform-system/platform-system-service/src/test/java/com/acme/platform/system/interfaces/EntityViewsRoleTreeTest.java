package com.acme.platform.system.interfaces;

import com.acme.platform.system.api.dto.AdminRoleView;
import com.acme.platform.system.domain.SysRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色树组装测试（角色父子关系树状定义：根哨兵 0 落库，树经 TreeBuilder 组装，环 / 重复 id / 孤儿显式失败）。
 * REQ-20261125-230807（F7）：响应侧根角色 parentId wire 归一 null（对齐 REQ-20261124-213008 §API），
 * 非根角色保留真实父 id——平铺与树形两形态锚点。
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
        // 根角色 parentId 0→null wire 归一（REQ-20261124-213008 §API / REQ-20261125-230807 AC-12）
        assertThat(tree.get(0).parentId()).isNull();
        // 子角色按入参顺序（用例层按 id 升序出库）嵌套，保留真实父 id
        assertThat(tree.get(0).children()).extracting(AdminRoleView::roleCode)
                .containsExactly("dev", "ops");
        assertThat(tree.get(0).children().get(0).parentId()).isEqualTo(1L);
        assertThat(tree.get(0).children().get(0).children()).isEmpty();
    }

    @Test
    void flatViewNormalizesRootParentIdToNullAndKeepsChildParentId() {
        // 根哨兵 0 仅落库；wire 平铺响应根角色 parentId 归一 null（REQ-20261125-230807 AC-12 锚点）
        AdminRoleView root = EntityViews.roleView(role(1L, 0L, "ops"));
        assertThat(root.parentId()).isNull();
        assertThat(root.children()).isEmpty();
        // 非根角色 wire 保留真实父 id
        assertThat(EntityViews.roleView(role(2L, 1L, "dev")).parentId()).isEqualTo(1L);
    }

    @Test
    void emptyListBuildsEmptyTree() {
        assertThat(EntityViews.roleTree(List.of())).isEmpty();
    }

    private static SysRole role(Long id, Long parentId, String code) {
        SysRole role = new SysRole(parentId, code, code, null, false);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(role, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return role;
    }
}
