package com.acme.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TreeBuilder 契约测试（FR-005、DP-004 本次定稿）：环/重复 id/孤儿显式失败。
 */
class TreeBuilderTest {

    record N(Object id, Object parentId) {
    }

    @Test
    void buildsTree() {
        List<TreeNode<N>> roots = TreeBuilder.build(
                List.of(new N(1, null), new N(2, 1), new N(3, 1), new N(4, 2)),
                N::id, N::parentId);
        assertEquals(1, roots.size());
        assertEquals(2, roots.get(0).children().size());
        assertEquals(1, roots.get(0).children().get(0).children().size());
    }

    @Test
    void rejectsDuplicateId() {
        assertThrows(IllegalArgumentException.class, () -> TreeBuilder.build(
                List.of(new N(1, null), new N(1, null)), N::id, N::parentId));
    }

    @Test
    void rejectsOrphan() {
        assertThrows(IllegalArgumentException.class, () -> TreeBuilder.build(
                List.of(new N(2, 99)), N::id, N::parentId));
    }

    @Test
    void rejectsSelfLoop() {
        assertThrows(IllegalArgumentException.class, () -> TreeBuilder.build(
                List.of(new N(1, 1)), N::id, N::parentId));
    }

    @Test
    void rejectsMultiNodeCycle() {
        assertThrows(IllegalArgumentException.class, () -> TreeBuilder.build(
                List.of(new N(1, 2), new N(2, 3), new N(3, 1)), N::id, N::parentId));
    }

    @Test
    void rejectsCycleWithTail() {
        // 4→1 的尾链挂进 1→2→3→1 环：整链参与环判定
        assertThrows(IllegalArgumentException.class, () -> TreeBuilder.build(
                List.of(new N(1, 2), new N(2, 3), new N(3, 1), new N(4, 1)), N::id, N::parentId));
    }
}
