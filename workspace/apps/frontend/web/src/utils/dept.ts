import type { AdminDeptView } from '@acme/core';

/**
 * 部门树展示层工具（REQ-20261114-120104）：部门管理/用户页/角色页共用的
 * 树展平与计数（AntD Select 下拉选项与树表 total 口径）。
 */

export interface DeptOption {
  value: string;
  label: string;
}

export interface FlattenDeptOptions {
  /** 排除某节点及其子树（如编辑时排除自身防止自挂父级）。 */
  excludeId?: string;
  /** 仅保留启用部门（岗位/角色部门集分配语义；禁用部门仅作筛选展示）。 */
  enabledOnly?: boolean;
}

/** 展平部门树为下拉选项（含层级缩进，全角空格缩进对齐 MenusPage 父级下拉惯例）。 */
export function flattenDeptOptions(
  nodes: AdminDeptView[],
  options: FlattenDeptOptions = {},
  depth = 0,
): DeptOption[] {
  const { excludeId, enabledOnly } = options;
  const out: DeptOption[] = [];
  for (const node of nodes) {
    if (String(node.id) !== excludeId && (!enabledOnly || node.status === 'ENABLED')) {
      out.push({ value: String(node.id), label: `${'　'.repeat(depth)}${node.deptName}` });
    }
    if (node.children?.length) {
      out.push(...flattenDeptOptions(node.children, options, depth + 1));
    }
  }
  return out;
}

/** 统计部门树节点总数（树形全量列表的 total 口径）。 */
export function countDeptTree(nodes: AdminDeptView[]): number {
  return nodes.reduce((sum, node) => sum + 1 + countDeptTree(node.children ?? []), 0);
}

/** 部门 TreeSelect 选项节点（treeData 形态；叶子不携带 children 键，避免无效展开箭头——AC-9）。
 *  pathTitle=选中回显层级路径（含自身、根在前「 / 」连接，如「研发中心 / 平台组」——AC-2），
 *  经 TreeSelect 原生 `treeNodeLabelProp="pathTitle"` 消费（antd 5.22 TreeSelectProps 无 labelRender）。 */
export interface DeptTreeOption {
  value: string;
  title: string;
  /** 层级路径（下拉选项仍渲染 title，仅选中回显消费该字段）。 */
  pathTitle: string;
  children?: DeptTreeOption[];
}

/** 部门树 → TreeSelect treeData（REQ-20261125-230807 AC-2）：层级由树形结构承载，
 *  选项文本无前导空格缩进；enabledOnly 剪枝禁用子树（表单分配语义仅启用部门）。
 *  pathTitle 由根递归累积祖先链（根在前），selected 回显与搜索均不影响。 */
export function toDeptTreeOptions(
  nodes: AdminDeptView[],
  options: { enabledOnly?: boolean } = {},
  /** 祖先显示名链（根在前；递归累积，外部调用不传）。 */
  ancestorNames: string[] = [],
): DeptTreeOption[] {
  const { enabledOnly } = options;
  return nodes
    .filter((node) => !enabledOnly || node.status === 'ENABLED')
    .map((node) => {
      const path = [...ancestorNames, node.deptName];
      const children = toDeptTreeOptions(node.children ?? [], options, path);
      const pathTitle = path.join(' / ');
      return children.length > 0
        ? { value: String(node.id), title: node.deptName, pathTitle, children }
        : { value: String(node.id), title: node.deptName, pathTitle };
    });
}
