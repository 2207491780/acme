/** @vitest-environment jsdom */
/**
 * REQ-20261125-230807 AC-8/AC-9/AC-16 —— 树形叶子无效展开图标剥离 + 菜单仅浏览契约用例。
 *
 * 覆盖映射（映射总表见 WT/.ai-tmp/req-230807/frontend-tester-delivery.md）：
 *   AC-8   平铺视图树表叶子（角色/部门/菜单）无子集行无折叠/展开图标；有子集行图标正常。
 *          判据=叶子行展开图标为 antd 占位（-spaced）或不存在；父行图标非占位。
 *   AC-9   三处 TreeSelect/树形控件（角色授权抽屉菜单树、套餐表单菜单树、父租户下拉）：
 *          无子集节点无展开箭头（switcher=noop 占位）；有子节点 switcher 正常。
 *   AC-16  菜单管理页仅浏览：无新建/加子菜单/编辑/删除入口；树表+筛选可用；叶子无展开图标；
 *          无 mutation 请求发出。
 *
 * 装配照范本 web/tests/REQ20261124213008-adminFrontendPages.test.tsx；树表展开图标选择器参照
 * 范本 A-4（.ant-table-row-expand-icon）；树 switcher 判据用 [class*="-tree-switcher"] 容纳
 * Tree（ant-tree-switcher-noop）与 TreeSelect（ant-select-tree-switcher-noop）两型（pitfall 9：
 * a11y 树与 DOM 镜像差 → 按渲染树结构节点断言）。
 *
 * 【表面假定（红则 TL 裁决，pitfall 14 口径）】
 *   A-1 飞行器（Menus 族 crudPages）负载敏感：本文件每用例独立装配，红则先隔离单测复跑判别（委派单钉死事实③）。
 *   A-2 树控件默认展开到可见（treeDefaultExpandAll 或默认展开根层）；若父节点折叠则先点开再断言叶子。
 *   A-3 实现未落地属预期红（钉死事实⑥）：stripEmptyChildren/仅浏览剥离均为本 REQ 新增面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import type { ComponentType } from 'react';
import { MenusPage } from '../src/pages/MenusPage';
import { DeptsPage } from '../src/pages/DeptsPage';
import { RolesPage } from '../src/pages/RolesPage';
import { TenantsPage } from '../src/pages/TenantsPage';
import { TenantPackagesPage } from '../src/pages/TenantPackagesPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 45000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

const setupApi = (routes: Record<string, (r: ReturnType<typeof asRequest>) => Response>) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    for (const [part, handler] of Object.entries(routes)) {
      if (r.url.pathname.includes(part)) {
        return handler(r);
      }
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock };
};

const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('min-width'), media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

const seedMe = async (permissions: string[]): Promise<void> => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: {
      id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
      status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
    },
    roles: ['super_admin'],
    permissions,
    menus: [],
  })));
  await useMeStore.getState().load();
};

const renderPage = async (Component: ComponentType, permissions: string[], routes?: Record<string, (r: ReturnType<typeof asRequest>) => Response>) => {
  await seedMe(permissions);
  const api = routes ? setupApi(routes) : setupApi({});
  render(<AntApp><Component /></AntApp>);
  return api;
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

const treeNodeOf = (title: HTMLElement) =>
  (title.closest('[class*="-tree-treenode"]') ?? title.closest('li')) as HTMLElement;

/** 树形表叶子/父行展开图标断言（范本 A-4 口径：叶子=-spaced 占位或不存在；父行=非占位）。
 *  A-2 兜底落地（B-组修复）：树表默认收起时先逐层点开折叠行再断言叶子（用例自述口径，
 *  数据驱动、层数上限 6，断言强度不变）。 */
const expandAllCollapsedRows = async () => {
  for (let depth = 0; depth < 6; depth += 1) {
    const collapsed = Array.from(document.querySelectorAll('.ant-table-row-expand-icon-collapsed')) as HTMLElement[];
    if (collapsed.length === 0) break;
    for (const icon of collapsed) fireEvent.click(icon);
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
};

const expectTreeRowIcons = async (leafAnchors: string[], parentAnchor: string) => {
  await screen.findByText(parentAnchor);
  await expandAllCollapsedRows();
  for (const leaf of leafAnchors) {
    await waitFor(() => {
      const row = screen.getByText(leaf).closest('tr') as HTMLElement;
      const icon = row.querySelector('.ant-table-row-expand-icon') as HTMLElement | null;
      const isPlaceholder = icon === null || icon.className.includes('ant-table-row-expand-icon-spaced');
      expect(isPlaceholder, `叶子行「${leaf}」不应渲染有效展开图标（children:[] truthy 判树形 → 预期红）`).toBe(true);
    });
  }
  const parentRow = screen.getByText(parentAnchor).closest('tr') as HTMLElement;
  const parentIcon = parentRow.querySelector('.ant-table-row-expand-icon') as HTMLElement | null;
  expect(parentIcon, `父行「${parentAnchor}」应保留展开图标`).toBeTruthy();
  expect(parentIcon!.className).not.toContain('ant-table-row-expand-icon-spaced');
};

/** 树/TreeSelect 节点 switcher 断言：叶子=noop 占位（无箭头），父=可展开。 */
const expectTreeNodeSwitchers = async (scope: HTMLElement | Document, leafAnchors: string[], parentAnchor: string) => {
  const findTitle = (text: string) => Array.from(scope.querySelectorAll('[class*="-tree-title"], .ant-select-tree-title, .ant-tree-title'))
    .find((el) => (el.textContent ?? '').trim() === text) as HTMLElement | undefined;

  const parentTitle = await waitFor(() => {
    const el = findTitle(parentAnchor);
    expect(el, `树节点「${parentAnchor}」应渲染`).toBeTruthy();
    return el!;
  });
  const parentNode = treeNodeOf(parentTitle);
  const parentSwitcher = parentNode.querySelector('[class*="-tree-switcher"]') as HTMLElement | null;
  expect(parentSwitcher, '父节点应有展开箭头').toBeTruthy();
  if (parentSwitcher && /close/.test(parentSwitcher.className)) {
    fireEvent.click(parentSwitcher);
  }

  for (const leaf of leafAnchors) {
    await waitFor(() => {
      const leafTitle = findTitle(leaf);
      expect(leafTitle, `叶子节点「${leaf}」应渲染`).toBeTruthy();
      const switcher = treeNodeOf(leafTitle!).querySelector('[class*="-tree-switcher"]') as HTMLElement | null;
      const isNoop = switcher === null || /noop/.test(switcher.className);
      expect(isNoop, `叶子节点「${leaf}」不应有展开箭头（空 children 未剥 → 预期红）`).toBe(true);
    });
  }
  // 展开后父节点 switcher 应为非 noop（有子节点正常可展开）
  await waitFor(() => {
    const switcher = treeNodeOf(parentTitle).querySelector('[class*="-tree-switcher"]') as HTMLElement | null;
    expect(switcher, '父节点应有展开箭头').toBeTruthy();
    expect(/noop/.test(switcher!.className), '父节点 switcher 不应为 noop 占位').toBe(false);
  });
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— fixtures（契约：树响应 children 恒为数组，叶子 []） —————
const SF_MENU_DIR = '9223372036854775911';
const SF_MENU_PAGE = '9223372036854775912';
const SF_MENU_BTN = '9223372036854775913';

const MENU_TREE = [
  {
    id: SF_MENU_DIR, parentId: null, menuName: '系统管理', menuType: 'DIRECTORY', path: null, component: null,
    icon: null, sortOrder: 1, visible: true, permissionCode: null, version: '0',
    children: [
      {
        id: SF_MENU_PAGE, parentId: SF_MENU_DIR, menuName: '用户管理', menuType: 'MENU', path: '/users', component: 'UsersPage',
        icon: null, sortOrder: 1, visible: true, permissionCode: 'system:user:list', version: '0',
        children: [
          { id: SF_MENU_BTN, parentId: SF_MENU_PAGE, menuName: '新增用户', menuType: 'BUTTON', path: null, component: null, icon: null, sortOrder: 1, visible: true, permissionCode: 'system:user:create', version: '0', children: [] },
        ],
      },
    ],
  },
  { id: '9223372036854775919', parentId: null, menuName: '工作台', menuType: 'MENU', path: '/dashboard', component: null, icon: null, sortOrder: 2, visible: true, permissionCode: null, version: '0', children: [] },
];

const DEPT_TREE = [
  {
    id: '9223372036854775811', parentId: null, deptName: '研发中心', sortOrder: 1, status: 'ENABLED', version: '0',
    children: [{ id: '9223372036854775812', parentId: '9223372036854775811', deptName: '平台组', sortOrder: 1, status: 'ENABLED', version: '0', children: [] }],
  },
  { id: '9223372036854775813', parentId: null, deptName: '市场部', sortOrder: 2, status: 'ENABLED', version: '0', children: [] },
];

const ROLE_FLAT_ROWS = [
  { id: '9223372036854775901', parentId: '0', roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED', builtIn: true, remark: '', version: '0', children: [] },
  { id: '9223372036854775903', parentId: '0', roleCode: 'ops', roleName: '运维员', status: 'ENABLED', builtIn: false, remark: '', version: '0', children: [] },
];

const SF_T_ROOT = '9223372036854775931';
const SF_T_CHILD = '9223372036854775932';
const SF_T_OTHER = '9223372036854775933';
const SF_PKG_A = '9223372036854775941';

const TENANT_TREE = [
  {
    id: SF_T_ROOT, tenantCode: 'acme', tenantName: '总部', status: 'ENABLED', level: 0, parentId: null,
    packageId: SF_PKG_A, version: '0', createdAt: '2026-01-01T00:00:00Z', children: [
      { id: SF_T_CHILD, tenantCode: 'acme_east', tenantName: '华东分部', status: 'ENABLED', level: 1, parentId: SF_T_ROOT, packageId: null, version: '0', createdAt: '2026-01-02T00:00:00Z', children: [] },
    ],
  },
  { id: SF_T_OTHER, tenantCode: 'standalone', tenantName: '独立租户', status: 'ENABLED', level: 0, parentId: null, packageId: null, version: '0', createdAt: '2026-01-03T00:00:00Z', children: [] },
];

const TENANT_ROWS = [
  { id: SF_T_ROOT, tenantCode: 'acme', tenantName: '总部', status: 'ENABLED', level: 0, parentId: null, packageId: SF_PKG_A, version: '0', createdAt: '2026-01-01T00:00:00Z' },
  { id: SF_T_CHILD, tenantCode: 'acme_east', tenantName: '华东分部', status: 'ENABLED', level: 1, parentId: SF_T_ROOT, packageId: null, version: '0', createdAt: '2026-01-02T00:00:00Z' },
  { id: SF_T_OTHER, tenantCode: 'standalone', tenantName: '独立租户', status: 'ENABLED', level: 0, parentId: null, packageId: null, version: '0', createdAt: '2026-01-03T00:00:00Z' },
];

const menusRoutes = () => ({
  '/system/menus': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope(MENU_TREE);
    return envelope(null);
  },
});

const deptsRoutes = () => ({
  '/system/depts': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope(DEPT_TREE);
    return envelope(null);
  },
});

const rolesRoutes = () => ({
  '/system/roles/tree': () => envelope([]),
  '/system/roles': () => envelope({ records: ROLE_FLAT_ROWS, total: ROLE_FLAT_ROWS.length }),
});

// ————— AC-8：平铺树表叶子无展开图标（菜单/部门/角色三面） —————
describe('REQ20261125230807 平铺树表叶子图标（AC-8）', () => {
  it('should_render_leaf_rows_without_effective_expand_icon_when_menu_tree_table_loaded', async () => {
    await renderPage(MenusPage, ['system:menu:list'], menusRoutes());
    // 父=系统管理/用户管理；叶=新增用户(BUTTON)/工作台
    await expectTreeRowIcons(['新增用户', '工作台'], '系统管理');
  });

  it('should_render_leaf_rows_without_effective_expand_icon_when_dept_tree_table_loaded', async () => {
    await renderPage(DeptsPage, ['system:dept:list'], deptsRoutes());
    await expectTreeRowIcons(['平台组', '市场部'], '研发中心');
  });

  it('should_render_flat_role_rows_without_any_effective_expand_icon', async () => {
    await renderPage(RolesPage, ['system:role:list'], rolesRoutes());
    await screen.findByText('超级管理员');
    // 平铺全为叶子（children:[]）→ 全表无有效展开图标（剥离后 expand 列可整体消失）
    await waitFor(() => {
      const icons = Array.from(document.querySelectorAll('.ant-table-row-expand-icon')) as HTMLElement[];
      const allPlaceholder = icons.every((el) => el.className.includes('ant-table-row-expand-icon-spaced'));
      expect(allPlaceholder, '平铺叶子行不得有有效展开图标').toBe(true);
    });
  });
});

// ————— AC-9：三处 TreeSelect/树形控件叶子无展开箭头 —————
describe('REQ20261125230807 TreeSelect 叶子箭头剥离（AC-9）', () => {
  /** 打开表单域下拉（label 候选定位 → mouseDown selector → 取可见浮层）。 */
  const openSelectByLabel = async (scope: HTMLElement | Document, labels: string[]) => {
    const labelEls = labels.flatMap((label) => Array.from(scope.querySelectorAll('label')).filter((el) => new RegExp(label).test(el.textContent ?? '')));
    expect(labelEls.length, `应存在 label ${labels.join('/')}`).toBeGreaterThanOrEqual(1);
    const item = (labelEls[0].closest('.ant-form-item') ?? labelEls[0].parentElement) as HTMLElement;
    fireEvent.mouseDown(item.querySelector('.ant-select-selector') as Element);
    return await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '下拉浮层应展开').toBeTruthy();
      return el as HTMLElement;
    });
  };

  it('should_strip_leaf_arrows_in_authorize_drawer_menu_tree_when_roles_page_rendered', async () => {
    const rolesAuthRoutes = {
      '/system/roles/tree': () => envelope([]),
      '/system/roles': () => envelope({ records: ROLE_FLAT_ROWS, total: ROLE_FLAT_ROWS.length }),
      '/system/menus': () => envelope(MENU_TREE),
    };
    await renderPage(RolesPage, ['system:role:list', 'system:role:grant', 'system:menu:list'], rolesAuthRoutes);
    const row = await findRowByText('超级管理员');
    fireEvent.click(await within(row).findByText(/授\s*权/));
    const drawer = await waitFor(() => {
      const el = document.querySelector('.ant-drawer-content');
      expect(el, '授权抽屉应打开').toBeTruthy();
      return el as HTMLElement;
    });
    await expectTreeNodeSwitchers(drawer, ['工作台'], '系统管理');
  });

  it('should_strip_leaf_arrows_in_package_form_menu_tree_when_packages_page_rendered', async () => {
    const packagesRoutes = {
      '/system/tenant-packages': () => envelope({ records: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }], total: 1 }),
      '/system/menus': () => envelope(MENU_TREE),
      '/system/capabilities': () => envelope([]),
    };
    await renderPage(TenantPackagesPage, ['system:tenant-package:list', 'system:tenant-package:create'], packagesRoutes);
    fireEvent.click(await screen.findByRole('button', { name: /新\s*建/ }));
    const dialog = await screen.findByRole('dialog');
    // 套餐表单菜单树=弹窗内联 Tree（AC-24 限高容器形态），非 TreeSelect 下拉浮层——
    // B-组定位修复（归因=用例错，pitfall 9 口径按渲染树断言）；断言强度不变（同一 expectTreeNodeSwitchers）
    await expectTreeNodeSwitchers(dialog, ['工作台'], '系统管理');
  });

  it('should_strip_leaf_arrows_in_parent_tenant_select_when_tenant_form_opened', async () => {
    const tenantRoutes = {
      '/system/tenants/tree': () => envelope(TENANT_TREE),
      '/system/tenants': (r: ReturnType<typeof asRequest>) => {
        if (r.method === 'GET') return envelope({ records: TENANT_ROWS, total: TENANT_ROWS.length });
        return envelope(null);
      },
      '/system/tenant-packages': () => envelope({ records: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }], total: 1 }),
    };
    await renderPage(TenantsPage, ['system:tenant:list', 'system:tenant:create'], tenantRoutes);
    fireEvent.click(await screen.findByRole('button', { name: /新\s*建(\s*租\s*户)?$/ }));
    const dialog = await screen.findByRole('dialog');
    const dropdown = await openSelectByLabel(dialog, ['父\\s*租\\s*户', '上级租户']);
    await expectTreeNodeSwitchers(dropdown, ['华东分部', '独立租户'], '总部');
  });
});

// ————— AC-16：菜单管理仅浏览 —————
describe('REQ20261125230807 MenusPage 仅浏览（AC-16）', () => {
  it('should_strip_all_mutation_entries_but_keep_tree_and_filters_when_menus_page_rendered', async () => {
    const { fetchMock } = await renderPage(MenusPage, ['system:menu:list'], menusRoutes());
    await screen.findByText('系统管理');

    // 仅浏览：无新建/加子菜单/编辑/删除任何入口
    expect(screen.queryByText(/新\s*建/), '不应有新建入口').toBeNull();
    expect(screen.queryByText(/子\s*菜\s*单|加\s*子|子\s*级/), '不应有加子菜单入口').toBeNull();
    expect(screen.queryByText(/^编\s*辑$/), '不应有编辑入口').toBeNull();
    expect(screen.queryByText(/^删\s*除$/), '不应有删除入口').toBeNull();

    // 树表渲染 + 筛选可用（关键字/类型/状态/可见性任一筛选控件保留）
    expect(document.querySelectorAll('.ant-select, .ant-table-tbody').length).toBeGreaterThan(0);

    // 无子集节点无展开图标（同 AC-8 口径，随 AC-16 复核）
    await expectTreeRowIcons(['新增用户', '工作台'], '系统管理');

    // 仅浏览页面不发出 mutation 请求
    expect(callsOf(fetchMock, 'POST', '/admin/v1/system/menus')).toHaveLength(0);
    expect(callsOf(fetchMock, 'PUT', '/admin/v1/system/menus')).toHaveLength(0);
    expect(callsOf(fetchMock, 'DELETE', '/admin/v1/system/menus')).toHaveLength(0);
  });
});
