package com.acme.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 通用树节点（FR-005）。不出现业务名词（BR-005）；父子关系标识由调用方约定。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class TreeNode<T> {

    private final Object id;
    private final Object parentId;
    private final T payload;
    private final List<TreeNode<T>> children = new ArrayList<>();

    public TreeNode(Object id, Object parentId, T payload) {
        this.id = id;
        this.parentId = parentId;
        this.payload = payload;
    }

    public Object id() {
        return id;
    }

    public Object parentId() {
        return parentId;
    }

    public T payload() {
        return payload;
    }

    /**
     * 子节点视图（FR-005）。
     */
    public List<TreeNode<T>> children() {
        return children;
    }

    /** 供 {@link TreeBuilder} 组装父子关系（模块内使用）。 */
    void addChild(TreeNode<T> child) {
        children.add(Objects.requireNonNull(child, "child 不能为空（DP-004）"));
    }
}
