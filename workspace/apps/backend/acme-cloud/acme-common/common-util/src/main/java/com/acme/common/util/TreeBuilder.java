package com.acme.common.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 树构建（FR-005）：平铺列表 → 树视图（根集合返回）。通用节点，不出现业务名词（BR-005）。
 * 环（自环与多节点环）、重复 id、孤儿节点一律显式失败（DP-004 本次定稿）。
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public final class TreeBuilder {

    private TreeBuilder() {
    }

    /**
     * 平铺列表构建为树视图（FR-005）；环/重复 id/孤儿节点显式失败（DP-004）。
     */
    public static <T> List<TreeNode<T>> build(Collection<T> nodes,
                                              Function<T, Object> idFn,
                                              Function<T, Object> parentIdFn) {
        Objects.requireNonNull(nodes, "nodes 不能为空（DP-004）");
        Objects.requireNonNull(idFn, "idFn 不能为空（DP-004）");
        Objects.requireNonNull(parentIdFn, "parentIdFn 不能为空（DP-004）");
        Map<Object, TreeNode<T>> byId = new LinkedHashMap<>();
        for (T node : nodes) {
            Object id = Objects.requireNonNull(idFn.apply(node), "节点 id 不能为空（DP-004）");
            if (byId.put(id, new TreeNode<>(id, parentIdFn.apply(node), node)) != null) {
                throw new IllegalArgumentException("节点 id 重复（DP-004）：" + id);
            }
        }
        List<TreeNode<T>> roots = new ArrayList<>();
        for (TreeNode<T> treeNode : byId.values()) {
            Object parentId = treeNode.parentId();
            if (parentId == null) {
                roots.add(treeNode);
                continue;
            }
            TreeNode<T> parent = byId.get(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("孤儿节点，父节点不存在（DP-004）：" + parentId);
            }
            if (parent == treeNode) {
                throw new IllegalArgumentException("节点自环（DP-004）：" + parentId);
            }
            parent.addChild(treeNode);
        }
        rejectCycles(byId);
        return roots;
    }

    /** 多节点环检测（DP-004 本次定稿）：沿父链着色，路径内重复命中即成环，显式失败。 */
    private static <T> void rejectCycles(Map<Object, TreeNode<T>> byId) {
        Set<Object> verified = new HashSet<>();
        for (TreeNode<T> start : byId.values()) {
            if (verified.contains(start.id())) {
                continue;
            }
            Set<Object> path = new LinkedHashSet<>();
            TreeNode<T> cursor = start;
            while (cursor != null && !verified.contains(cursor.id())) {
                if (!path.add(cursor.id())) {
                    throw new IllegalArgumentException("节点成环（DP-004）：" + path);
                }
                Object parentId = cursor.parentId();
                cursor = parentId == null ? null : byId.get(parentId);
            }
            verified.addAll(path);
        }
    }
}
