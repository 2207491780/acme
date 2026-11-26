/** @vitest-environment jsdom */
/**
 * REQ-20261125-230807 AC-6/AC-7/AC-10/AC-11 —— RolesPage 授权抽屉/父级三态/写后失效契约用例。
 *
 * 覆盖映射（映射总表见 WT/.ai-tmp/req-230807/frontend-tester-delivery.md）：
 *   AC-6   授权抽屉权限点选择列表化：域分组展示、关键字过滤（匹配 code/name）、过滤结果全选/反选、
 *          已选计数可见、total>单页上限给出「目录可能不完整」告警。
 *   AC-7   菜单授权级联：勾父级联勾子级、取消父级联取消；提交 menuIds 含勾选节点及父链（BUTTON 叶联动）。
 *   AC-10  编辑角色清空父级 → wire parentId=0 哨兵（null=不调整/0=移为根/>0=改父三态）；
 *          设父 → 父 ID 字符串原样；mock 与真后端同形（根归一 null）。
 *   AC-11  平铺侧写操作成功后切树形视图重新拉树（写后失效，非旧快照）；树形手动刷新保留。
 *
 * 装配照范本 web/tests/REQ20261124213008-adminFrontendPages.test.tsx（RolesPage 先例：平铺/树形
 * Segmented、树形表行首 .ant-table-row-expand-icon 展开、切换等挂载防旧表行命中 A-4 口径）。
 * 下拉/树交互照 pitfalls 卡 7/8/9；负例与文案宽容匹配（pitfall 13/14）。
 *
 * 【表面假定（红则 TL 裁决，pitfall 14 口径）】
 *   A-1 授权抽屉入口=行内「授权」按钮；抽屉内同时含权限点选择列表与菜单授权树两块。
 *   A-2 域分组标题=权限码首段（system/infra/workflow…）；已选计数文案宽容 /已选\D*\d+/。
 *   A-3 截断告警文案宽容 /目录可能不完整|可能不完整/（契约原文「目录可能不完整」）。
 *   A-4 编辑弹窗父级域 label 候选 父角色/上级角色/父级；清空交互=allowClear 悬停点击，缺省则候选
 *       「顶级/根」选项兜底，两者皆缺=预期红披露。
 *   A-5 树形手动刷新入口候选：文本「刷新」/aria-label 刷新/.anticon-reload 图标按钮。
 *   A-6 实现未落地属预期红（钉死事实⑥）：列表化选择器/级联/哨兵 0/写后失效均为本 REQ 新增面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import type { ComponentType } from 'react';
import { RolesPage } from '../src/pages/RolesPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 45000 });

const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const AUTHORIZE_BUTTON = /授\s*权/;
const EDIT_BUTTON = /^编\s*辑$/;

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

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> | null => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return null;
  }
};

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

/** 树形控件节点行定位（Tree/TreeSelect 通用：标题 → 最近的 treenode 容器）。 */
const treeNodeOf = (title: HTMLElement) =>
  (title.closest('[class*="-tree-treenode"]') ?? title.closest('li')) as HTMLElement;

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

// ————— fixtures（契约 §API：雪花字符串 id；根角色 wire parentId=null 归一（AC-12 口径，mock 同形）） —————
const SF_ROLE_ROOT = '9223372036854775901';
const SF_ROLE_CHILD = '9223372036854775902';
const SF_ROLE_LEAF = '9223372036854775903';
const SF_MENU_DIR = '9223372036854775911';
const SF_MENU_PAGE = '9223372036854775912';
const SF_MENU_BTN = '9223372036854775913';

const makeRole = (id: string, parentId: string | null, roleCode: string, roleName: string, builtIn = false) => ({
  id, parentId, roleCode, roleName, status: 'ENABLED', builtIn, remark: '', version: '0', children: [],
});

const FLAT_ROWS = [
  makeRole(SF_ROLE_ROOT, '0', 'super_admin', '超级管理员', true),
  makeRole(SF_ROLE_CHILD, SF_ROLE_ROOT, 'sec_officer', '安全员'),
  makeRole(SF_ROLE_LEAF, '0', 'ops', '运维员'),
];

const ROLE_TREE = [
  {
    ...makeRole(SF_ROLE_ROOT, null, 'super_admin', '超级管理员', true),
    children: [{ ...makeRole(SF_ROLE_CHILD, SF_ROLE_ROOT, 'sec_officer', '安全员'), children: [] }],
  },
  makeRole(SF_ROLE_LEAF, null, 'ops', '运维员'),
];

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

const permRow = (id: string, code: string, name: string, httpMethod: string | null, httpPath: string | null) => ({
  id, permissionCode: code, permissionName: name, permissionType: 'API', status: 'ENABLED',
  httpMethod, httpPath, version: '0',
});

const PERMISSION_ROWS = [
  permRow('7001', 'system:user:list', '用户查询', 'GET', '/admin/v1/system/users'),
  permRow('7002', 'system:user:create', '用户新增', 'POST', '/admin/v1/system/users'),
  permRow('7003', 'system:role:list', '角色查询', 'GET', '/admin/v1/system/roles'),
  permRow('7004', 'system:role:update', '角色更新', 'PUT', '/admin/v1/system/roles/{roleId}'),
  permRow('7005', 'infra:file:list', '文件查询', 'GET', '/admin/v1/infra/files'),
  permRow('7006', 'infra:file:upload', '文件上传', 'POST', '/admin/v1/infra/files/upload'),
  permRow('7007', 'workflow:task:list', '待办查询', 'GET', '/admin/v1/workflow/tasks/todo'),
  permRow('7008', 'workflow:task:operate', '待办办理', 'POST', '/admin/v1/workflow/tasks/{taskId}/complete'),
  permRow('7009', 'system:menu:sync', '菜单同步', 'PATCH', '/admin/v1/system/menus/sort'),
  permRow('7010', 'system:cache:evict', '缓存清理', 'DELETE', '/admin/v1/system/cache'),
];

const PERMS_SEED = ['system:role:list', 'system:role:update', 'system:role:grant', 'system:permission:list', 'system:menu:list'];

type AuthState = { boundMenuIds: string[]; boundPermissionIds?: string[] };

const roleAuthRoutes = (state: AuthState = { boundMenuIds: [] }) => ({
  // 长前缀先匹配：角色级端点
  '/system/roles/9223372036854775901/permissions': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'PUT') {
      state.boundPermissionIds = ((parseBody(r.body)?.permissionIds as string[]) ?? []).map(String);
      return envelope(null);
    }
    return envelope([PERMISSION_ROWS[0], PERMISSION_ROWS[2]]);
  },
  '/system/roles/9223372036854775901/menus': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'PUT') {
      state.boundMenuIds = ((parseBody(r.body)?.menuIds as string[]) ?? []).map(String);
      return envelope(null);
    }
    return envelope(MENU_TREE);
  },
  '/system/roles/tree': () => envelope(ROLE_TREE),
  '/system/roles': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope({ records: FLAT_ROWS, total: FLAT_ROWS.length });
    if (r.method === 'PUT' && r.url.pathname.endsWith('/status')) return envelope(null);
    if (r.method === 'PUT') {
      // mock 与真后端同形：返回更新后视图（根 parentId 归一 null，AC-12 口径）
      const body = parseBody(r.body) ?? {};
      const roleId = r.url.pathname.split('/').pop() ?? '';
      return envelope({ ...FLAT_ROWS.find((row) => row.id === roleId), ...body, parentId: Number(body.parentId) === 0 ? null : body.parentId });
    }
    return envelope(null);
  },
  '/system/permissions': () => envelope({ records: PERMISSION_ROWS, total: 205 }),
  '/system/menus': () => envelope(MENU_TREE),
});

const openAuthorizeDrawer = async (): Promise<HTMLElement> => {
  const row = await findRowByText('超级管理员');
  // 入口形态候选（A-1）：行内文本「授权」→ aria/title「授权」→ 「更多」下拉菜单内「授权」
  const direct = within(row).queryByText(AUTHORIZE_BUTTON)
    ?? (row.querySelector('[aria-label*="授权"], [title*="授权"]') as HTMLElement | null);
  if (direct) {
    fireEvent.click(direct);
  } else {
    const more = within(row).queryByText(/更\s*多/)
      ?? (row.querySelector('[aria-label*="更多"], [title*="更多"], .ant-dropdown-trigger') as HTMLElement | null);
    expect(more, '行内应有「授权」入口（直显/aria/更多下拉，A-1）').toBeTruthy();
    fireEvent.click(more!);
    fireEvent.click(await screen.findByText(AUTHORIZE_BUTTON));
  }
  await waitFor(() => {
    expect(document.querySelector('.ant-drawer-content'), '授权抽屉应打开').toBeTruthy();
  });
  return document.querySelector('.ant-drawer-content') as HTMLElement;
};

// ————— AC-6：权限点选择列表化 —————
describe('REQ20261125230807 RolesPage 权限点列表化选择（AC-6）', () => {
  it('should_show_grouped_filterable_permission_list_with_counts_and_truncation_alert_when_drawer_opened', async () => {
    const state: AuthState = { boundMenuIds: [] };
    await renderPage(RolesPage, PERMS_SEED, roleAuthRoutes(state));
    const drawer = await openAuthorizeDrawer();

    // 域分组展示：分组标题按权限码首段（A-2 宽容）
    await waitFor(() => {
      const text = drawer.textContent ?? '';
      expect(text).toMatch(/system/i);
      expect(text).toMatch(/infra/i);
      expect(text).toMatch(/workflow/i);
    });
    expect(within(drawer).getByText('system:user:list')).toBeTruthy();

    // 关键字过滤（匹配 code/name）：输入「文件」→ 命中名称，system 域条目收敛
    const filterInput = drawer.querySelector('.ant-drawer-body input, input[placeholder]') as HTMLInputElement | null
      ?? within(drawer).getAllByRole('textbox')[0] as HTMLInputElement;
    fireEvent.change(filterInput, { target: { value: '文件' } });
    await waitFor(() => {
      expect(within(drawer).getByText('文件查询')).toBeTruthy();
      expect(within(drawer).queryByText('system:user:list'), '过滤应收敛未命中条目').toBeNull();
    });

    // 过滤结果全选/反选 + 已选计数可见
    fireEvent.click(await within(drawer).findByRole('button', { name: /全\s*选/ }));
    await waitFor(() => {
      expect((drawer.textContent ?? '')).toMatch(/已选\D*2/);
    });
    fireEvent.click(await within(drawer).findByRole('button', { name: /反\s*选/ }));
    await waitFor(() => {
      expect((drawer.textContent ?? '')).toMatch(/已选\D*0/);
    });

    // total(205) > 单页上限 → 截断告警（A-3 宽容文案）
    expect((drawer.textContent ?? '')).toMatch(/目录可能不完整|可能不完整/);
  });
});

// ————— AC-7：菜单授权级联勾选 —————
describe('REQ20261125230807 RolesPage 菜单级联授权（AC-7）', () => {
  it('should_cascade_check_children_and_submit_full_menu_ids_when_menu_auth_saved', async () => {
    const state: AuthState = { boundMenuIds: [] };
    const { fetchMock } = await renderPage(RolesPage, PERMS_SEED, roleAuthRoutes(state));
    const drawer = await openAuthorizeDrawer();

    // 勾选父节点 系统管理 → 子级（用户管理、BUTTON 叶 新增用户）级联勾选
    const parentTitle = await within(drawer).findByText('系统管理');
    const parentNode = treeNodeOf(parentTitle);
    const parentCheckbox = parentNode.querySelector('[class*="-tree-checkbox"]') as HTMLElement;
    expect(parentCheckbox, '菜单树父节点应有勾选框').toBeTruthy();
    fireEvent.click(parentCheckbox);
    await waitFor(() => {
      const childNode = treeNodeOf(within(drawer).getByText('用户管理'));
      expect(childNode.querySelector('[class*="-tree-checkbox-checked"]'), '子菜单应级联勾选').toBeTruthy();
      const btnNode = treeNodeOf(within(drawer).getByText('新增用户'));
      expect(btnNode.querySelector('[class*="-tree-checkbox-checked"]'), 'BUTTON 叶应随父联动勾选').toBeTruthy();
    });

    // 取消父级 → 级联取消子级
    fireEvent.click(parentCheckbox);
    await waitFor(() => {
      const childNode = treeNodeOf(within(drawer).getByText('用户管理'));
      expect(childNode.querySelector('[class*="-tree-checkbox-checked"]'), '取消父级应级联取消子级').toBeNull();
    });

    // 重新勾选父级并保存 → 提交 menuIds 含全部勾选节点及父链（BUTTON 叶随父绑定），雪花字符串原样
    fireEvent.click(parentCheckbox);
    await waitFor(() => {
      expect(treeNodeOf(within(drawer).getByText('用户管理')).querySelector('[class*="-tree-checkbox-checked"]')).toBeTruthy();
    });
    fireEvent.click(await within(drawer).findByRole('button', { name: /保\s*存|确\s*定/ }));
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT', `/admin/v1/system/roles/${SF_ROLE_ROOT}/menus`);
      expect(puts).toHaveLength(1);
      const menuIds = ((parseBody(puts[0]?.body)?.menuIds as string[]) ?? []).map(String);
      for (const id of [SF_MENU_DIR, SF_MENU_PAGE, SF_MENU_BTN]) {
        expect(menuIds, `提交应含勾选节点及父链 ${id}（字符串原样）`).toContain(id);
      }
    });
  });
});

// ————— AC-10：父级三态（清空=哨兵 0；设父=字符串原样） —————
describe('REQ20261125230807 RolesPage 父级三态（AC-10）', () => {
  it('should_send_parent_id_zero_sentinel_when_role_parent_cleared', async () => {
    const { fetchMock } = await renderPage(RolesPage, PERMS_SEED, roleAuthRoutes());
    const row = await findRowByText('安全员');
    fireEvent.click(await within(row).findByText(EDIT_BUTTON));
    const dialog = await screen.findByRole('dialog');

    // 父级当前=超级管理员 → 清空（allowClear 优先，缺省兜底「顶级」候选——A-4）
    const parentLabels = Array.from(dialog.querySelectorAll('label')).filter((el) => /父\s*角\s*色|上级\s*角\s*色|父\s*级/.test(el.textContent ?? ''));
    expect(parentLabels.length, '编辑弹窗应有父级域').toBeGreaterThanOrEqual(1);
    const parentItem = parentLabels[0].closest('.ant-form-item') as HTMLElement;
    const selector = parentItem.querySelector('.ant-select-selector') as HTMLElement;
    fireEvent.mouseEnter(selector);
    const clearIcon = parentItem.querySelector('.ant-select-clear') as HTMLElement | null;
    if (clearIcon) {
      // antd clear 在 jsdom 下需 mouseDown 触发 onChange(undefined)（click 不生效）
      fireEvent.mouseDown(clearIcon);
      fireEvent.mouseUp(clearIcon);
      fireEvent.click(clearIcon);
    } else {
      fireEvent.mouseDown(selector);
      const dropdown = await waitFor(() => {
        const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
        expect(el, '父级下拉应展开').toBeTruthy();
        return el as HTMLElement;
      });
      const rootOption = dropdown.querySelector('.ant-select-item-option[title*="顶级"], .ant-select-item-option[title*="根"]')
        ?? Array.from(dropdown.querySelectorAll('.ant-select-item-option')).find((el) => /顶级|根|无/.test(el.textContent ?? ''));
      expect(rootOption, '无 allowClear 时应存在「顶级」候选（A-4）').toBeTruthy();
      fireEvent.click(rootOption as Element);
    }

    fireEvent.click(await within(dialog).findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT', `/admin/v1/system/roles/${SF_ROLE_CHILD}`);
      expect(puts).toHaveLength(1);
      const body = parseBody(puts[0]?.body) ?? {};
      expect(Number(body.parentId), '清空父级应 wire parentId=0 哨兵（现状：初始值 Number 数值化未清 → 预期红）').toBe(0);
      expect(body.parentId, '哨兵 0 不得为 null/undefined').not.toBeNull();
      expect(body.parentId).not.toBeUndefined();
    });
  });

  it('should_send_parent_id_string_when_role_parent_set', async () => {
    const { fetchMock } = await renderPage(RolesPage, PERMS_SEED, roleAuthRoutes());
    const row = await findRowByText('运维员');
    fireEvent.click(await within(row).findByText(EDIT_BUTTON));
    const dialog = await screen.findByRole('dialog');

    const parentLabels = Array.from(dialog.querySelectorAll('label')).filter((el) => /父\s*角\s*色|上级\s*角\s*色|父\s*级/.test(el.textContent ?? ''));
    const parentItem = parentLabels[0].closest('.ant-form-item') as HTMLElement;
    fireEvent.mouseDown(parentItem.querySelector('.ant-select-selector') as Element);
    const dropdown = await waitFor(() => {
      const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
      expect(el, '父级下拉应展开').toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(within(dropdown).getByText('超级管理员'));

    fireEvent.click(await within(dialog).findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT', `/admin/v1/system/roles/${SF_ROLE_LEAF}`);
      expect(puts).toHaveLength(1);
      const body = parseBody(puts[0]?.body) ?? {};
      expect(body.parentId, '设父应传父角色雪花字符串原样（不得数值化）').toBe(SF_ROLE_ROOT);
    });
  });
});

// ————— AC-11：平铺写后切树形重新拉取 + 手动刷新保留 —————
describe('REQ20261125230807 RolesPage 树形写后失效（AC-11）', () => {
  it('should_refetch_role_tree_after_flat_mutation_when_switching_to_tree_view', async () => {
    const { fetchMock } = await renderPage(RolesPage, PERMS_SEED, roleAuthRoutes());
    await findRowByText('超级管理员');
    expect(callsOf(fetchMock, 'GET', '/admin/v1/system/roles/tree')).toHaveLength(0);

    // 平铺侧写操作 1：编辑保存成功
    const row1 = await findRowByText('安全员');
    fireEvent.click(await within(row1).findByText(EDIT_BUTTON));
    const dialog1 = await screen.findByRole('dialog');
    fireEvent.click(await within(dialog1).findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      expect(callsOf(fetchMock, 'PUT', `/admin/v1/system/roles/${SF_ROLE_CHILD}`)).toHaveLength(1);
    });

    // 切树形 → 重新拉树（写后失效，非旧快照）
    fireEvent.click(screen.getAllByText('树形')[0]);
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/roles/tree')).toHaveLength(1);
    });

    // 回平铺再写一次（编辑保存），再切树形 → 再次重新拉树（非缓存快照）
    fireEvent.click(screen.getAllByText('平铺')[0]);
    const row2 = await findRowByText('运维员');
    fireEvent.click(await within(row2).findByText(EDIT_BUTTON));
    const dialog2 = await screen.findByRole('dialog');
    fireEvent.click(await within(dialog2).findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      expect(callsOf(fetchMock, 'PUT', `/admin/v1/system/roles/${SF_ROLE_LEAF}`)).toHaveLength(1);
    });
    fireEvent.click(screen.getAllByText('树形')[0]);
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/roles/tree').length).toBeGreaterThanOrEqual(2);
    });

    // 树形手动刷新保留（A-5 候选定位）
    const refresh = screen.queryAllByText(/刷\s*新/)[0]
      ?? (document.querySelector('[aria-label*="刷新" i], [title*="刷新"], .anticon-reload') as HTMLElement | null)?.closest('button') as HTMLElement | null;
    expect(refresh, '树形视图应保留手动刷新入口（A-5）').toBeTruthy();
    const before = callsOf(fetchMock, 'GET', '/admin/v1/system/roles/tree').length;
    fireEvent.click(refresh as HTMLElement);
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/roles/tree').length).toBeGreaterThan(before);
    });
  });
});
