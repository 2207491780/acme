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
