// 通用 UI 样例库数据（增量五 F7）—— 组织树 / 穿梭池 / 级联选项；纪律同前。
// 成员节点对齐 data.ts 的 MEMBERS，级联版本由正式 store 推导。
import type { AppState } from '../types';
import { MEMBERS, type Member } from './data';

export interface OrgNode {
  key: string;
  label: string;
  /** 叶子挂成员角色；分组节点无 role。 */
  role?: Member['role'];
  children?: OrgNode[];
}

export const ORG_TREE: OrgNode[] = [
  {
    key: 'org-acme',
    label: 'ACME 交付台（全组织）',
    children: [
      {
        key: 'org-web',
        label: 'Web 产品线',
        children: [
          { key: 'u-lin', label: '林晚', role: 'dev' },
          { key: 'u-zhou', label: '周衍', role: 'qa' },
        ],
      },
      {
        key: 'org-desktop',
        label: '桌面产品线',
        children: [{ key: 'u-chen', label: '陈屿', role: 'ops' }],
      },
      {
        key: 'org-mobile',
        label: '移动产品线',
        children: [{ key: 'u-su', label: '苏然', role: 'dev' }],
      },
      {
        key: 'org-qa',
        label: '质量与效能',
        children: [
          { key: 'u-he', label: '何青', role: 'owner' },
          { key: 'u-tang', label: '唐简', role: 'viewer' },
        ],
      },
    ],
  },
];

export interface TransferItem {
  key: string;
  label: string;
  group: string;
}

/** 穿梭框演示池：权限项 → 成员分配（group 按应用分组）。 */
export const TRANSFER_POOL: TransferItem[] = [
  { key: 'perm-publish-web', label: '执行发布', group: 'Web 控制台' },
  { key: 'perm-rollback-web', label: '执行回滚', group: 'Web 控制台' },
  { key: 'perm-approve-desktop', label: '审批签核', group: 'Electron 桌面端' },
  { key: 'perm-deploy-desktop', label: '部署安装包', group: 'Electron 桌面端' },
  { key: 'perm-gray-mobile', label: '灰度放量', group: '移动端' },
  { key: 'perm-freeze-mobile', label: '设置冻结期', group: '移动端' },
];

export interface CascaderOption {
  value: string;
  label: string;
  children?: CascaderOption[];
}

/** 级联选项：环境 → 应用 → 该应用在此环境的已发布版本（正式 store 推导）。 */
export function cascaderOptions(state: Pick<AppState, 'releases' | 'apps'>): CascaderOption[] {
  return (['dev', 'test', 'prod'] as const).map((env) => ({
    value: env,
    label: { dev: '开发', test: '测试', prod: '生产' }[env],
    children: state.apps.map((app) => {
      const versions = [
        ...new Set(
          state.releases.filter((r) => r.env === env && r.appId === app.id).map((r) => `v${r.version}`),
        ),
      ];
      return {
        value: app.id,
        label: app.name,
        children: versions.map((v) => ({ value: `${app.id}@${env}@${v}`, label: v })),
      };
    }),
  }));
}

export function collectOrgKeys(nodes: OrgNode[]): string[] {
  return nodes.flatMap((n) => [n.key, ...(n.children ? collectOrgKeys(n.children) : [])]);
}

/** 组织树叶子成员名（供页面校验与工作台联动）。 */
export const ORG_LEAF_MEMBERS: string[] = collectOrgKeys(ORG_TREE)
  .filter((k) => k.startsWith('u-'))
  .map((key) => MEMBERS.find((m) => m.id === key)?.name ?? key);
