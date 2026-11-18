/**
 * REQ-20261119-051119 AC-5：页面按钮 Auth 接线抽查回归（UsersPage + DictsPage）。
 *
 * 行动作/工具条按钮已由 `{crud.can(X) ? … : null}` 迁移为 `<Auth code="X">`（hide 模式，缺省）。
 * 本套件按「有权限=按钮齐全（DOM 与迁移前一致）」「无权限=不渲染」双向断言，锁定接线后的行为零变化。
 * 装配照既有范本：fetch stubGlobal 弱匹配路由 + useMeStore 权限播种 + <AntApp>（usersPage 口径）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { UsersPage } from '../src/pages/UsersPage';
import { DictsPage } from '../src/pages/DictsPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const DELETE_BUTTON = /删\s*除/;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— fetch 边界 mock —————
const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase() };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase() };
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

/** UsersPage 数据面（GET /users 返回两行；其余端点回空信封）。 */
const setupUsersApi = () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (r.url.pathname.includes('/users')) {
      return envelope({
        records: [
          { id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null, status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0' },
          { id: '1002', username: 'ops', nickname: '运维员', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [], createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0' },
        ],
        total: 2,
      });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
};

/** DictsPage 数据面（类型域 /dict-types* 返回两行；条目端点回数组）。 */
const setupDictsApi = () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (r.url.pathname.includes('/dict-types') && !/\d/.test(r.url.pathname.split('/dict-types')[1] ?? '')) {
      return envelope({
        records: [
          { id: '3401', dictCode: 'user_status', dictName: '用户状态', status: 'ENABLED', remark: '', createdAt: '2026-01-01T00:00:00Z', version: '0' },
          { id: '3402', dictCode: 'gender', dictName: '性别字典', status: 'ENABLED', remark: '', createdAt: '2026-01-02T00:00:00Z', version: '0' },
        ],
        total: 2,
      });
    }
    return envelope([]);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
};

// 全量套件并行负载下默认 1s 查找窗口偏紧：统一放宽到 5s（crudPages 同型口径）
const rowOf = async (anchor: string) => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  return cell.closest('tr') as HTMLElement;
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

describe('REQ20261119051119 UsersPage Auth 接线（AC-5 抽查）', () => {
  it('should_render_all_wired_buttons_when_permissions_held', async () => {
    await seedMe(['system:user:list', 'system:user:create', 'system:user:update', 'system:user:delete', 'system:user:assign-role']);
    setupUsersApi();
    render(<AntApp><UsersPage /></AntApp>);

    const row = await rowOf('ops'); // 非内置行：四类动作按钮齐全
    expect(within(row).getByText(EDIT_BUTTON)).toBeTruthy();
    expect(within(row).getByText('分配角色')).toBeTruthy();
    expect(within(row).getByText(DELETE_BUTTON)).toBeTruthy();
    expect(screen.getByText('新建用户')).toBeTruthy();
  });

  it('should_hide_wired_buttons_when_permission_missing', async () => {
    await seedMe(['system:user:list']);
    setupUsersApi();
    render(<AntApp><UsersPage /></AntApp>);

    const row = await rowOf('ops');
    expect(within(row).queryByText(EDIT_BUTTON)).toBeNull();
    expect(within(row).queryByText('分配角色')).toBeNull();
    expect(within(row).queryByText(DELETE_BUTTON)).toBeNull();
    expect(screen.queryByText('新建用户')).toBeNull();
  });
});

describe('REQ20261119051119 DictsPage Auth 接线（AC-5 抽查）', () => {
  it('should_render_wired_buttons_when_permissions_held', async () => {
    await seedMe(['system:dict:list', 'system:dict:create', 'system:dict:update', 'system:dict:delete']);
    setupDictsApi();
    render(<AntApp><DictsPage /></AntApp>);

    const row = await rowOf('用户状态');
    expect(within(row).getByText(EDIT_BUTTON)).toBeTruthy();
    expect(within(row).getByText(DELETE_BUTTON)).toBeTruthy();
    // 工具条：类型「新建」+ 条目「新建条目」（选中类型后出现）
    expect((await screen.findAllByText(ADD_BUTTON, {}, { timeout: 5000 })).length).toBeGreaterThanOrEqual(1);
    fireEvent.click(within(row).getByText('用户状态'));
    expect(await screen.findByText('新建条目', {}, { timeout: 5000 })).toBeTruthy();
  });

  it('should_hide_wired_buttons_when_permission_missing', async () => {
    await seedMe(['system:dict:list']);
    setupDictsApi();
    render(<AntApp><DictsPage /></AntApp>);

    const row = await rowOf('用户状态');
    expect(within(row).queryByText(EDIT_BUTTON)).toBeNull();
    expect(within(row).queryByText(DELETE_BUTTON)).toBeNull();
    expect(screen.queryByText(ADD_BUTTON)).toBeNull();
    // 选中类型后（卡片标题承载「条目：用户状态（user_status）」）条目新建按钮仍不渲染
    fireEvent.click(within(row).getByText('用户状态'));
    expect(await screen.findByText(/条目：用户状态（user_status）/, {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.queryByText('新建条目')).toBeNull();
  });
});
