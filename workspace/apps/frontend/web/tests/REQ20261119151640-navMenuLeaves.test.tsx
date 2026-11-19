/**
 * REQ-20261119-151640 AC-4/5/6：六页菜单叶子导航投影回归（纯结构单测，直调 buildDynamicNav，
 * 不渲染 App、不依赖后端实现）。装配照 web/tests/REQ20261114120101-nav.test.tsx 的
 * BUTTON 纯结构段与 REQ20261119051119-menusButton.test.tsx 的剔除断言方式。
 *
 * 期望值全部取自契约 contracts/REQ-20261119-151640.md §变更清单明细表与确认记录①~④：
 * 六叶子字段逐条照明细表；BUTTON 子节点挂叶子下按确认④（30~32→40、33~35→41、36~37→42、38→43，
 * 叶内 sort 自 1 续编）。契约未给出 BUTTON 的名称/权限码明细 → fixture 用本地占位
 * （`按钮<id>` / `fixture:button:<id>`），仅用于「持码仍剔除」的结构断言，不构成数据期望。
 */
import { describe, expect, it } from 'vitest';
import { buildDynamicNav } from '../src/nav';
import { FALLBACK_ICON, resolveMenuIcon } from '../src/menuIcons';
import type { AdminMenuView } from '@acme/core';

const menuNode = (over: Partial<AdminMenuView> = {}): AdminMenuView => ({
  id: 0, parentId: 0, menuName: '', menuType: 'MENU', path: null, component: null, icon: null,
  sortOrder: 0, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

/** 契约明细表六叶子（id/parent_id/menu_name/path/component/icon/sort_order/permission_code）。 */
const LEAVES = [
  { id: 40, parentId: 1, menuName: '部门管理', path: '/depts', component: 'system/dept/index', icon: 'depts', sortOrder: 16, permissionCode: 'system:dept:list' },
  { id: 41, parentId: 1, menuName: '岗位管理', path: '/positions', component: 'system/position/index', icon: 'positions', sortOrder: 17, permissionCode: 'system:position:list' },
  { id: 42, parentId: 1, menuName: '通知公告', path: '/notices', component: 'system/notice/index', icon: 'notification', sortOrder: 18, permissionCode: 'system:notice:list' },
  { id: 43, parentId: 8, menuName: '文件管理', path: '/files', component: 'infra/file/index', icon: 'files', sortOrder: 5, permissionCode: 'infra:file:list' },
  { id: 44, parentId: 8, menuName: '登录日志', path: '/login-logs', component: 'auth/loginlog/index', icon: 'loginlogs', sortOrder: 6, permissionCode: 'auth:loginlog:list' },
  { id: 45, parentId: 8, menuName: '在线用户', path: '/online-users', component: 'auth/online/index', icon: 'onlineusers', sortOrder: 7, permissionCode: 'auth:online:list' },
];

const SIX_PATHS = LEAVES.map((l) => l.path);

/** 确认④：V20 临时直挂目录的 BUTTON 归位映射（buttonId → 新叶子 id）。 */
const BUTTON_PARENT: Record<number, number> = {
  30: 40, 31: 40, 32: 40,
  33: 41, 34: 41, 35: 41,
  36: 42, 37: 42,
  38: 43,
};

const buttonIdsOf = (leafId: number) =>
  Object.keys(BUTTON_PARENT).map(Number).filter((bid) => BUTTON_PARENT[bid] === leafId).sort((a, b) => a - b);

const buttonNode = (id: number, parentId: number, sortOrder: number): AdminMenuView => menuNode({
  id, parentId, menuName: `按钮${id}`, menuType: 'BUTTON', path: null,
  permissionCode: `fixture:button:${id}`, sortOrder,
});

const leafNode = (leaf: (typeof LEAVES)[number]): AdminMenuView => menuNode({
  ...leaf, menuType: 'MENU', visible: true, status: 'ENABLED',
  children: buttonIdsOf(leaf.id).map((bid, idx) => buttonNode(bid, leaf.id, idx + 1)),
});

/** 两级树：目录归属按确认①（depts/positions/notices→系统管理1；files/login-logs/online-users→平台运营8）。 */
const TREE: AdminMenuView[] = [
  menuNode({
    id: 1, parentId: 0, menuName: '系统管理', menuType: 'DIRECTORY', path: '/system', sortOrder: 1,
    children: LEAVES.filter((l) => l.parentId === 1).map(leafNode),
  }),
  menuNode({
    id: 8, parentId: 0, menuName: '平台运营', menuType: 'DIRECTORY', path: '/infra', sortOrder: 2,
    children: LEAVES.filter((l) => l.parentId === 8).map(leafNode),
  }),
];

/** 全量权限 = 六叶子码 + 全部 BUTTON fixture 码（证明持码 BUTTON 仍被剔除）。 */
const ALL_PERMS = [
  ...LEAVES.map((l) => l.permissionCode),
  ...Object.keys(BUTTON_PARENT).map((id) => `fixture:button:${id}`),
];

describe('REQ20261119151640 六页菜单叶子导航投影（AC-4/5/6）', () => {
  it('should_project_six_leaves_into_nav_entries_when_full_permissions_held', () => {
    // AC-4：entries 含六路径，label=menuName，icon 按注册表解析（非 FALLBACK_ICON）
    const nav = buildDynamicNav(TREE, ALL_PERMS);
    expect(nav).not.toBeNull();
    expect(nav!.entries.map((e) => e.path)).toEqual(expect.arrayContaining(SIX_PATHS));
    for (const leaf of LEAVES) {
      const entry = nav!.entries.find((e) => e.path === leaf.path);
      expect(entry, `nav entry for ${leaf.path}`).toBeTruthy();
      expect(entry!.label).toBe(leaf.menuName);
      expect(entry!.icon).not.toBe(FALLBACK_ICON);
      expect(entry!.icon).toBe(resolveMenuIcon(leaf.icon));
    }
  });

  // AC-5：逐一缺某叶子权限码 → 该叶子剔除，其余五叶子不受影响（fail-closed 回归）
  for (const leaf of LEAVES) {
    it(`should_exclude_leaf_${leaf.path.slice(1)}_when_its_permission_code_missing`, () => {
      const perms = ALL_PERMS.filter((p) => p !== leaf.permissionCode);
      const nav = buildDynamicNav(TREE, perms);
      expect(nav).not.toBeNull();
      const paths = nav!.entries.map((e) => e.path);
      expect(paths).not.toContain(leaf.path);
      for (const other of LEAVES.filter((l) => l.id !== leaf.id)) {
        expect(paths).toContain(other.path);
      }
    });
  }

  it('should_exclude_button_nodes_from_entries_and_items_when_tree_contains_buttons', () => {
    // AC-6：BUTTON 即便持码也不进导航 entries/items（V20 口径回归，断言方式沿用范本）
    const nav = buildDynamicNav(TREE, ALL_PERMS);
    expect(nav).not.toBeNull();
    expect(nav!.entries.map((e) => e.path).sort()).toEqual([...SIX_PATHS].sort());
    const serialized = JSON.stringify(nav!.items);
    for (const id of Object.keys(BUTTON_PARENT)) {
      expect(serialized).not.toContain(`按钮${id}`);
    }
    for (const leaf of LEAVES) {
      expect(serialized).toContain(leaf.menuName);
    }
  });
});
