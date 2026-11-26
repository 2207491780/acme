/**
 * 树形展示层数据工具（REQ-20261125-230807）：antd Table 树表 / Tree / TreeSelect 数据源共用。
 *
 * 背景：后端树响应契约 children 恒为数组（叶子 []，DTO 兜底归一），而 antd/rc-table 与
 * rc-tree 均以「节点对象存在 children 键」truthy 判定树形行——空数组叶子会渲染出无效的
 * 展开/折叠图标。后端改契约影响面大，前端展示层剥空 children 为契约定案；树表与三处
 * TreeSelect/Tree 数据源统一走本工具收口（AC-8/AC-9）。
 */

/** 递归剥离树节点中的空 children 键：叶子节点摘除 children 键（变为普通行/普通节点），
 *  非空子树递归处理。不改动入参（返回新对象），无 children 键的节点原引用返回。 */
export function stripEmptyChildren<T extends { children?: T[] }>(nodes: T[]): T[] {
  return nodes.map((node) => {
    if (!Array.isArray(node.children)) {
      return node;
    }
    if (node.children.length === 0) {
      const next = { ...node };
      delete next.children;
      return next;
    }
    return { ...node, children: stripEmptyChildren(node.children) };
  });
}
