package com.acme.platform.system.api;

/**
 * 字典形态（REQ-20261125-230807 确认④；openapi 枚举按名序列化 / 反序列化，9.3.2 新增枚举值兼容）。
 * FLAT=平铺（存量默认，交互零变化）；TREE=树形（条目支持父子层级，选父校验同类型/存在/防环）。
 */
public enum DictKind {

    /** 平铺（存量默认；提交 parentId 拒绝）。 */
    FLAT,

    /** 树形（条目父子层级；条目读取嵌套 children 恒数组）。 */
    TREE
}
