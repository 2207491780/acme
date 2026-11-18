/**
 * REQ-20261119-051119 AC-7/AC-9：MenusPage BUTTON 菜单节点回归。
 *
 * 覆盖：列表 BUTTON 行「按钮」Tag 展示；表单 menuTypes 含 BUTTON 选项；
 * menuType=BUTTON 时 permissionCode 必填校验（校验文案 + 拦截提交）与 path 可留空提示；
 * 通过校验后 POST body 携带 menuType=BUTTON + permissionCode。
 * 装配照既有范本 web/tests/REQ20261114120101-crudPages.test.tsx（fetch 弱匹配 + 权限播种 + AntApp）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { MenusPage } from '../src/pages/MenusPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

const ADD_BUTTON = /新\s*[建增]/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const BUTTON_OPTION = '按钮（操作权限）';
const PERMISSION_CODE_REQUIRED = '按钮节点必须挂权限码';
const PATH_OPTIONAL_HINT = '按钮节点无路由，路径可留空';

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

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  try {
    return JSON.parse(String(body ?? '')) as Record<string, unknown>;
  } catch {
    return {};
  }
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
    if (r.method === 'GET') {
      return envelope({ records: MENUS, total: MENUS.length });
    }
    if (r.method === 'POST') {
      const created: MenuFixture = { id: '9901', ...parseBody(r.body) };
      MENUS.push(created);
      return envelope(created);
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

/** 对话框内文本类输入框（排除 select 搜索框/hidden；crudPages 口径）。 */
const dialogTextboxes = (dialog: HTMLElement) =>
  Array.from(dialog.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input, textarea')).filter(
    (el) => !el.disabled && !el.readOnly
      && (el.type === 'text' || el.type === '' || el.type === 'number' || el.tagName === 'TEXTAREA'),
  );

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

  it('should_require_permission_code_when_menu_type_button_selected', async () => {
    await seedMe(['system:menu:list', 'system:menu:create']);
    const fetchMock = setupMenusApi();
    render(<AntApp><MenusPage /></AntApp>);

    await screen.findByText('系统管理', {}, { timeout: 5000 });
    fireEvent.click((await screen.findAllByText(ADD_BUTTON, {}, { timeout: 5000 }))[0]);
    const dialog = await screen.findByRole('dialog', {}, { timeout: 5000 });

    // 类型选 BUTTON
    fireEvent.mouseDown(within(dialog).getByLabelText('类型'));
    fireEvent.click(await screen.findByText(BUTTON_OPTION, {}, { timeout: 5000 }));
    await waitFor(() => {
      const item = document.querySelector('.ant-select-selection-item');
      expect(item?.textContent ?? '').toContain(BUTTON_OPTION);
    }, { timeout: 5000 });

    // path 非必填提示出现（BUTTON 无路由语义）
    expect(await within(dialog).findByText(PATH_OPTIONAL_HINT, {}, { timeout: 5000 })).toBeTruthy();

    // 菜单名称已填，权限码留空提交 → 校验文案 + 无 POST
    // 提交走原生 form submit 事件（rc-field-form 自身吞掉校验 rejection；页面 onOk 路径会产生 unhandled rejection 噪音）
    fireEvent.change(dialogTextboxes(dialog)[0]!, { target: { value: '按钮节点X' } });
    fireEvent.submit(dialog.querySelector('form')!);
    expect(await screen.findByText(PERMISSION_CODE_REQUIRED, {}, { timeout: 5000 })).toBeTruthy();
    expect(fetchMock.mock.calls.filter(([input, init]) => {
      const r = asRequest(input as RequestInfo | URL, init);
      return r.method === 'POST' && r.url.pathname.includes('/menus');
    })).toHaveLength(0);
  });

  it('should_submit_button_node_with_permission_code_when_valid', async () => {
    await seedMe(['system:menu:list', 'system:menu:create']);
    const fetchMock = setupMenusApi();
    render(<AntApp><MenusPage /></AntApp>);

    await screen.findByText('系统管理', {}, { timeout: 5000 });
    fireEvent.click((await screen.findAllByText(ADD_BUTTON, {}, { timeout: 5000 }))[0]);
    const dialog = await screen.findByRole('dialog', {}, { timeout: 5000 });

    fireEvent.mouseDown(within(dialog).getByLabelText('类型'));
    fireEvent.click(await screen.findByText(BUTTON_OPTION, {}, { timeout: 5000 }));

    fireEvent.change(dialogTextboxes(dialog)[0]!, { target: { value: '按钮节点Y' } });
    fireEvent.change(within(dialog).getByLabelText('权限码'), { target: { value: 'system:dict:create' } });
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }, { timeout: 5000 }));

    await waitFor(() => {
      const posts = fetchMock.mock.calls.map(([input, init]) => asRequest(input as RequestInfo | URL, init))
        .filter((r) => r.method === 'POST' && r.url.pathname.includes('/menus'));
      expect(posts).toHaveLength(1);
      expect(parseBody(posts[0]?.body).menuType).toBe('BUTTON');
      expect(parseBody(posts[0]?.body).permissionCode).toBe('system:dict:create');
    }, { timeout: 5000 });
  });
});
