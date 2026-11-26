/**
 * REQ-20261119-051119 AC-7/AC-9：MenusPage BUTTON 菜单节点回归。
 *
 * REQ-20261125-230807 delta-r3 基线更新（AC-16 仅浏览）：菜单页无新建/加子菜单/编辑/删除入口，
 * 原“新建 BUTTON 节点表单校验/提交”两用例的表单语义已随契约移除；重写为仅浏览断言——
 * （a）仅浏览权限下树表/BUTTON Tag/筛选可用且零 mutation 调用；（b）即便授予 mutation 权限，
 * 入口仍不存在（前端收口与权限无关）。原 BUTTON Tag 展示用例保留。
 * 装配照既有范本 web/tests/REQ20261114120101-crudPages.test.tsx（fetch 弱匹配 + 权限播种 + AntApp）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { MenusPage } from '../src/pages/MenusPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

const BUTTON_OPTION = '按钮（操作权限）';

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

/** fixture 节点（宽松键面：仅服务渲染文本断言，无结构断言依赖）。 */
type MenuFixture = Record<string, unknown>;

const menuNode = (over: MenuFixture = {}): MenuFixture => ({
  id: '0', parentId: '0', menuName: '', menuType: 'MENU', path: null, component: null, icon: null,
  sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0',
  ...over,
});

const MENUS: MenuFixture[] = [
  menuNode({
    id: '3301', parentId: '0', menuName: '系统管理', menuType: 'DIRECTORY', path: '/system',
    children: [
      menuNode({ id: '3302', parentId: '3301', menuName: '用户中心', menuType: 'MENU', path: '/users', permissionCode: 'system:user:list' }),
      menuNode({ id: '3303', parentId: '3301', menuName: '用户新建按钮', menuType: 'BUTTON', path: null, permissionCode: 'system:user:create' }),
    ],
  }),
];

const setupMenusApi = () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes('/menus')) return envelope(null);
    // GET 全量树（REQ-20261125-230807 确认②：mock mutation handler 已随仅浏览收口移除）
    if (r.method === 'GET') {
      return envelope({ records: MENUS, total: MENUS.length });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
};

const stubBrowserGaps = () => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

const seedMe = async (permissions: string[]) => {
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

// 全量套件并行负载下默认 1s 查找窗口偏紧：统一放宽到 5s（crudPages 同型口径）
const rowOf = async (anchor: string) => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  return cell.closest('tr') as HTMLElement;
};

/** mutation 调用计数（POST/PUT/DELETE 任意路径）。 */
const mutationCallsOf = (fetchMock: ReturnType<typeof vi.fn>) =>
  (fetchMock.mock.calls as unknown as [input: RequestInfo | URL, init?: RequestInit][])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => ['POST', 'PUT', 'DELETE'].includes(r.method));

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

describe('REQ20261119051119 MenusPage BUTTON 节点（AC-7）', () => {
  it('should_render_button_tag_for_button_type_rows', async () => {
    await seedMe(['system:menu:list']);
    setupMenusApi();
    render(<AntApp><MenusPage /></AntApp>);

    const dirRow = await rowOf('系统管理');
    // 树表子行（MENU/BUTTON 均为 DIRECTORY 子节点）默认折叠 → 展开根行
    fireEvent.click(dirRow.querySelector('.ant-table-row-expand-icon')!);
    expect(await screen.findByText('用户新建按钮', {}, { timeout: 5000 })).toBeTruthy();
    // BUTTON 行的「按钮」Tag（purple）
    expect(screen.getByText('按钮')).toBeTruthy();
  });

  it('should_not_render_mutation_entries_when_menus_page_browse_only', async () => {
    await seedMe(['system:menu:list']);
    const fetchMock = setupMenusApi();
    render(<AntApp><MenusPage /></AntApp>);

    const dirRow = await rowOf('系统管理');
    // 树表可用：展开根行可见 MENU/BUTTON 子行；BUTTON 行「按钮」Tag 保留
    fireEvent.click(dirRow.querySelector('.ant-table-row-expand-icon')!);
    expect(await screen.findByText('用户新建按钮', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.getByText('按钮')).toBeTruthy();

    // AC-16：无新建/加子菜单/编辑/删除入口；无表单对话框（新建按 role=button 判定，避免撞 fixture 行名「用户新建按钮」）
    expect(screen.queryByRole('button', { name: /新\s*[建增]/ })).toBeNull();
    expect(screen.queryAllByText(/子\s*菜\s*单/)).toHaveLength(0);
    expect(screen.queryAllByText(/编\s*辑/)).toHaveLength(0);
    expect(screen.queryAllByText(/删\s*除/)).toHaveLength(0);
    expect(screen.queryByRole('dialog')).toBeNull();

    // 筛选可用：关键字搜索框 + 类型筛选下拉在文档中
    expect(document.querySelector('input[placeholder="搜索菜单名称/路径/权限码"]')).toBeTruthy();
    expect(screen.getAllByLabelText('菜单类型').length).toBeGreaterThanOrEqual(1);

    // 零 mutation 调用（渲染+展开全程）
    await waitFor(() => expect(mutationCallsOf(fetchMock)).toHaveLength(0));
  });

  it('should_keep_entries_absent_even_when_mutation_permissions_granted', async () => {
    // 前端收口与权限无关（AC-16）：授予全部菜单 mutation 权限，入口仍不存在
    await seedMe(['system:menu:list', 'system:menu:create', 'system:menu:update', 'system:menu:delete']);
    const fetchMock = setupMenusApi();
    render(<AntApp><MenusPage /></AntApp>);

    await rowOf('系统管理');
    expect(screen.queryByRole('button', { name: /新\s*[建增]/ })).toBeNull();
    expect(screen.queryAllByText(/子\s*菜\s*单/)).toHaveLength(0);
    expect(screen.queryAllByText(/编\s*辑/)).toHaveLength(0);
    expect(screen.queryAllByText(/删\s*除/)).toHaveLength(0);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByText(BUTTON_OPTION)).toBeNull(); // 类型选项仅存在于（已移除的）表单
    await waitFor(() => expect(mutationCallsOf(fetchMock)).toHaveLength(0));
  });
});
