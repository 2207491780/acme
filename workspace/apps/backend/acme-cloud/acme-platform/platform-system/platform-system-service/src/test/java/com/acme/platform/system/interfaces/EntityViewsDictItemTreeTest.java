package com.acme.platform.system.interfaces;

import com.acme.common.util.TreeBuilder;
import com.acme.platform.system.api.DictKind;
import com.acme.platform.system.api.EnableStatus;
import com.acme.platform.system.api.dto.AdminDictItemView;
import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.domain.SysDictType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字典项视图组装测试（REQ-20261125-230807 确认④）：kind=TREE 条目读取嵌套 children 恒数组、
 * kind=FLAT 平铺现状不变（wire 不披露 children 键）；环 / 孤儿数据显式失败（TreeBuilder DP-004 同款）。
 */
class EntityViewsDictItemTreeTest {

    /** wire 序列化口径（与 web 层同 Jackson3 线并注册 DictItemViewWireModule，仅用于 children 键披露断言）。 */
    private static final JsonMapper WIRE_JSON = JsonMapper.builder()
            .addModule(new DictItemViewWireModule())
            .build();

    @Test
    void treeKindBuildsNestedChildrenAlwaysArray() {
        SysDictType tree = new SysDictType("region", "地区", null, DictKind.TREE);
        SysDictItem root = item(1L, tree, "华东", null);
        SysDictItem child = item(2L, tree, "上海", 1L);
        SysDictItem grand = item(3L, tree, "浦东", 2L);

        List<AdminDictItemView> views = EntityViews.dictItemViews(DictKind.TREE, List.of(root, child, grand));

        assertThat(views).hasSize(1);
        AdminDictItemView rootNode = views.get(0);
        assertThat(rootNode.itemValue()).isEqualTo("华东");
        assertThat(rootNode.parentId()).isNull();
        assertThat(rootNode.children()).extracting(AdminDictItemView::itemValue).containsExactly("上海");
        assertThat(rootNode.children().get(0).parentId()).isEqualTo(1L);
        assertThat(rootNode.children().get(0).children()).extracting(AdminDictItemView::itemValue)
                .containsExactly("浦东");
        // 叶子 children 恒数组（树响应契约：无子集行不出现 children 缺失）
        assertThat(rootNode.children().get(0).children().get(0).children()).isEmpty();
    }

    @Test
    void flatKindKeepsFlatListWithoutChildrenKey() {
        SysDictType flat = new SysDictType("gender", "性别", null, DictKind.FLAT);
        SysDictItem m = item(1L, flat, "男", null);
        SysDictItem f = item(2L, flat, "女", null);

        List<AdminDictItemView> views = EntityViews.dictItemViews(DictKind.FLAT, List.of(m, f));

        assertThat(views).hasSize(2);
        assertThat(views).extracting(AdminDictItemView::itemValue).containsExactly("男", "女");
        assertThat(views).extracting(AdminDictItemView::parentId).containsOnlyNulls();
        // 平铺行为零变化（V35 前 wire 无 children 键）：对象层 children=null，序列化不披露该键
        assertThat(views).extracting(AdminDictItemView::children).containsOnlyNulls();
        for (AdminDictItemView view : views) {
            assertThat(WIRE_JSON.readTree(WIRE_JSON.writeValueAsString(view)).path("children").isMissingNode())
                    .as("FLAT 平铺列表 wire 不应披露 children 键：%s", view).isTrue();
        }
    }

    @Test
    void flatViewOmitsChildrenKeyOnWire() {
        // 平铺 / 创建 / 修改响应：children 不披露（V35 前 wire 无 children 键，零变化锚）
        SysDictType flat = new SysDictType("gender", "性别", null, DictKind.FLAT);
        AdminDictItemView view = EntityViews.dictItemView(item(1L, flat, "男", null));
        assertThat(view.children()).isNull();
        assertThat(view.parentId()).isNull();
        assertThat(WIRE_JSON.readTree(WIRE_JSON.writeValueAsString(view)).path("children").isMissingNode())
                .as("FLAT 单条 wire 不应披露 children 键：%s", view).isTrue();
    }

    @Test
    void treeLeafKeepsEmptyChildrenArrayOnWire() {
        // kind=TREE 叶子 wire children 恒数组（读侧契约，叶子 []）
        SysDictType tree = new SysDictType("region", "地区", null, DictKind.TREE);
        AdminDictItemView leaf = EntityViews.dictItemViews(DictKind.TREE, List.of(item(1L, tree, "浦东", null)))
                .get(0);
        assertThat(WIRE_JSON.readTree(WIRE_JSON.writeValueAsString(leaf)).path("children").isArray()).isTrue();
    }

    @Test
    void orphanParentFailsExplicitly() {
        SysDictType tree = new SysDictType("region", "地区", null, DictKind.TREE);
        SysDictItem orphan = item(1L, tree, "孤儿", 99L);
        assertThatThrownBy(() -> EntityViews.dictItemViews(DictKind.TREE, List.of(orphan)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("孤儿");
    }

    private static SysDictItem item(long id, SysDictType type, String value, Long parentId) {
        SysDictItem item = new SysDictItem(type, value, value, 0);
        item.setParentId(parentId);
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(item, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return item;
    }
}
