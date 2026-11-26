package com.acme.platform.system.api.dto;

import com.acme.platform.system.api.EnableStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 字典项视图（同类型下 itemValue 唯一）。
 * REQ-20261125-230807 增补 parentId / children：parentId=父条目 id（FLAT 恒 null，TREE 顶层 null）；
 * children 仅 kind=TREE 条目读取披露（嵌套子条目，叶子恒 []）——平铺读 / 创建 / 修改响应传 null，
 * wire 不披露 children 键（V35 前平铺 wire 无该键，零变化锚；键省略由 platform-system-service
 * {@code DictItemViewWireModule} mixin NON_NULL 承载，-api 模块不引 Jackson 依赖）。
 */
public record AdminDictItemView(Long id, Long dictTypeId, Long parentId, String itemLabel, String itemValue,
                                Integer sortOrder, EnableStatus status, Long version,
                                List<AdminDictItemView> children) {

    /** children 直通（null=不披露，平铺 wire 零变化；TREE 读传实际子集，叶子空数组）。 */
    public AdminDictItemView {
        children = children == null ? null : new ArrayList<>(children);
    }
}
