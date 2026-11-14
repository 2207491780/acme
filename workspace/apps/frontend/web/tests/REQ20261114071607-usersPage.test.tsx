/**
 * REQ-20261114-071607 AC-19：UsersPage 迁移 CrudTable/CrudFormModal 后行为等价回归（组件测试）。
 *
 * 契约行为清单（AC-19）：列表渲染 / 搜索触发分页 / 新增 Modal 提交 / 编辑回填 / 删除确认 / 无权限项隐藏。
 * 装配照既有范本：web/tests/meStore.test.ts（fetch 边界 stubGlobal mock）+ 权限经 useMeStore 播种。
 *
 * 【表面口径（TL 裁决⑨对齐，前端工人已核实的行为事实）】
 *   - authStore/meStore 经 @acme/core 引入（AC-16 下沉后 web/src/store/* 路径已删除）
 *   - UsersPage 为命名导出 export function UsersPage（web/src/pages/UsersPage）
 *   - 权限点：USER_LIST / USER_CREATE / USER_UPDATE / USER_DELETE
 *   - 文案：搜索占位「搜索账号」、新建按钮「新建用户」、账号表单 label「账号」；
 *     nickname 相关交互保持行为式（按对话框内既值/次序定位，不假设 label 文案）
 *   - 列表响应形状 { records, total }；搜索参数名 username
 *
 * 【残留假定（低风险）】增删改成功后刷新列表；编辑/删除 id 走路径参数（mock 路由弱匹配维持）；
 * 按钮文案常量以正则兼容 antd 两字 autoInsertSpace 插空与 zh_CN/en 确认按钮；渲染须包 <AntApp>（App.useApp 消息上下文）。
 *
 * 【环境留档（round2）】jsdom25+cssstyle4.6 把 animation/transition 注册为原型访问器 → rc-motion
 * supportTransition=true 但 transitionend 永不派发，antd Modal 又不传 motionDeadline → 关闭动效永不结束、
 * 关闭态 DOM 永不卸载。未来所有 antd 关闭态断言一律数据驱动（接口调用/列表刷新），勿断言 dialog 从 DOM 卸载。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { UsersPage } from '../src/pages/UsersPage';
import { useAuthStore, useMeStore } from '@acme/core';

// jsdom+antd 重 DOM 下 renderPage 装配约 2.5-3s，5s 缺省预算过紧（round2 打回单①），本文件 6 条统一放宽。
vi.setConfig({ testTimeout: 15000 });

// ————— 表面常量（TL 裁决⑨对齐后的实际口径） —————
const USERS_PATH_PART = '/users';        // users 接口 URL 公共子串（弱匹配路由用）
const SEARCH_PLACEHOLDER = '搜索账号';    // 搜索框 placeholder
const ADD_BUTTON_TEXT = '新建用户';       // 新建入口按钮
const EDIT_BUTTON_TEXT = /编\s*辑/;      // 行内编辑动作（兼容 antd 两字按钮 autoInsertSpace 插空）
const DELETE_BUTTON_TEXT = /删\s*除/;    // 行内删除动作（同上）
const LABEL_USERNAME = '账号';           // 账号表单 label
const PERM = {
  list: 'USER_LIST',
  create: 'USER_CREATE',
  update: 'USER_UPDATE',
  delete: 'USER_DELETE',
};
const ALL_PERMS = [PERM.list, PERM.create, PERM.update, PERM.delete];
const CONFIRM_BUTTON = /确\s*定|^OK$/i;  // 兼容 zh_CN「确 定/确定」与 en「OK」

// ————— fixtures —————
type UserRow = {
  id: string; username: string; nickname: string; email: string | null; phone: string | null;
  status: 'ENABLED'; builtIn: boolean; roleIds: string[]; createdAt: string; updatedAt: string; version: string;
};

const userRow = (id: string, username: string, nickname: string): UserRow => ({
  id, username, nickname, email: null, phone: null, status: 'ENABLED', builtIn: false,
  roleIds: [], createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0',
});

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— fetch 边界 mock（API 层 mock） —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];
type AnyFetchMock = ReturnType<typeof vi.fn>;
type ParsedCall = { url: URL; method: string; body: BodyInit | null | undefined };

const asRequest = (input: RequestInfo | URL, init?: RequestInit): ParsedCall => {
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

const callsOf = (fetchMock: AnyFetchMock, method: string): ParsedCall[] =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(USERS_PATH_PART) && r.method === method);

const lastQuery = (fetchMock: AnyFetchMock): URLSearchParams | null =>
  callsOf(fetchMock, 'GET').at(-1)?.url.searchParams ?? null;

const setupApi = (rows: UserRow[]) => {
  const state = { rows: [...rows] };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (r.method === 'GET' && r.url.pathname.includes(USERS_PATH_PART)) {
      return envelope({ records: state.rows, total: state.rows.length });
    }
    if (r.method === 'POST' && r.url.pathname.includes(USERS_PATH_PART)) {
      const body = parseBody(r.body);
      const created = userRow('2001', String(body.username ?? 'newuser'), String(body.nickname ?? '新用户'));
      state.rows = [...state.rows, created];
      return envelope(created);
    }
    if ((r.method === 'PUT' || r.method === 'DELETE') && r.url.pathname.includes(`${USERS_PATH_PART}/`)) {
      const id = r.url.pathname.split('/').filter(Boolean).pop() ?? '';
      if (r.method === 'DELETE') {
        state.rows = state.rows.filter((item) => item.id !== id);
        return envelope(null);
      }
      const patch = parseBody(r.body);
      state.rows = state.rows.map((item) =>
        item.id === id ? { ...item, nickname: typeof patch.nickname === 'string' ? patch.nickname : item.nickname } : item);
      return envelope(state.rows.find((item) => item.id === id) ?? null);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

// ————— 装配 —————
const stubBrowserGaps = () => {
  // AntD5 在 jsdom 下的两处环境缺口
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
    user: userRow('1001', 'admin', '超级管理员'),
    roles: ['super_admin'],
    permissions,
    menus: [],
  })));
  await useMeStore.getState().load();
};

const renderPage = async (permissions: string[]) => {
  await seedMe(permissions);
  const api = setupApi([userRow('1001', 'admin', '超级管理员'), userRow('1002', 'ops', '运维员')]);
  // 裸渲染时 antd5 App.useApp() 脱离 Provider 会得到空 message（message.error 非函数），
  // 页面成功分支即崩 → Modal 永不关；须包 <AntApp> 提供消息上下文（对齐 main.tsx 运行时形态）。
  render(<AntApp><UsersPage /></AntApp>);
  return api;
};

const findRowByUsername = async (username: string) => {
  const cell = await screen.findByText(username);
  return cell.closest('tr') as HTMLElement;
};

/** 对话框内文本输入框（不假设 label 文案；含禁用项以便回填断言） */
const dialogTextboxes = (dialog: HTMLElement) =>
  Array.from(dialog.querySelectorAll<HTMLInputElement>('input')).filter(
    (el) => el.type === 'text' || el.type === '',
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

describe('REQ20261114071607 UsersPage CRUD 范本回归（AC-19）', () => {
  it('should_render_list_rows_when_page_loads', async () => {
    const { fetchMock } = await renderPage(ALL_PERMS);
    expect(await screen.findByText('admin')).toBeTruthy();
    expect(screen.getByText('ops')).toBeTruthy();
    expect(lastQuery(fetchMock)?.get('page')).toBe('1');
  });

  it('should_reset_to_first_page_with_username_when_search_submitted', async () => {
    const { fetchMock } = await renderPage([PERM.list, PERM.update]);
    await screen.findByText('admin');
    const input = screen.getByPlaceholderText(SEARCH_PLACEHOLDER) as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'ops' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => {
      const query = lastQuery(fetchMock);
      expect(query?.get('username')).toBe('ops');
      expect(query?.get('page')).toBe('1');
    });
  });

  it('should_submit_create_modal_when_add_and_confirm_clicked', async () => {
    const { fetchMock } = await renderPage(ALL_PERMS);
    await screen.findByText('admin');
    fireEvent.click(screen.getByText(ADD_BUTTON_TEXT));
    const dialog = await screen.findByRole('dialog');
    const usernameInput = screen.getByLabelText(LABEL_USERNAME) as HTMLInputElement;
    fireEvent.change(usernameInput, { target: { value: 'newuser' } });
    // nickname 行为式填写：账号输入框之后第一个可用文本框（DTO 字段序 nickname 紧随 username）
    const restBox = dialogTextboxes(dialog).find((el) => !el.disabled && el !== usernameInput);
    fireEvent.change(restBox!, { target: { value: '新用户' } });
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST');
      expect(posts).toHaveLength(1);
      expect(parseBody(posts[0]?.body).username).toBe('newuser');
    });
    // 关闭态数据驱动断言（jsdom25+cssstyle4.6 下 antd 关闭态 DOM 永不卸载，见文件头环境留档）：
    // 等提交成功后的 reload 二次 GET，再由列表回显闭环成功分支。
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET').length).toBeGreaterThanOrEqual(2);
    });
    expect(await screen.findByText('newuser')).toBeTruthy();
  });

  it('should_prefill_and_submit_update_when_row_edit_clicked', async () => {
    const { fetchMock } = await renderPage([PERM.list, PERM.update, PERM.delete]);
    const rowEl = await findRowByUsername('admin');
    fireEvent.click(within(rowEl).getByText(EDIT_BUTTON_TEXT));
    const dialog = await screen.findByRole('dialog');
    // 等价行为（TL 裁决⑪）：username 无输入框展示位，回填以弹窗标题「编辑用户：admin」承载
    expect(await screen.findByText(/编辑用户：admin/)).toBeTruthy();
    // 回填行为式断言：nickname 文本框承载该行既有值（弹窗内为 nickname/email/phone 三框）
    const values = () => dialogTextboxes(dialog).map((el) => el.value);
    await waitFor(() => {
      expect(values()).toContain('超级管理员');
    });
    // 行为式定位 nickname 输入框：当前值恰为「超级管理员」的那个文本框
    const nicknameBox = dialogTextboxes(dialog).find((el) => el.value === '超级管理员')!;
    fireEvent.change(nicknameBox, { target: { value: '管理员改' } });
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT');
      expect(puts).toHaveLength(1);
      expect(puts[0]?.url.pathname.endsWith(`${USERS_PATH_PART}/1001`)).toBe(true);
      expect(parseBody(puts[0]?.body).nickname).toBe('管理员改');
    });
  });

  it('should_delete_row_after_confirm_when_row_delete_clicked', async () => {
    const { fetchMock } = await renderPage([PERM.list, PERM.delete]);
    const rowEl = await findRowByUsername('admin');
    fireEvent.click(within(rowEl).getByText(DELETE_BUTTON_TEXT));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => {
      const deletes = callsOf(fetchMock, 'DELETE');
      expect(deletes).toHaveLength(1);
      expect(deletes[0]?.url.pathname.endsWith(`${USERS_PATH_PART}/1001`)).toBe(true);
    });
    await waitFor(() => expect(screen.queryByText('admin')).toBeNull());
    expect(screen.getByText('ops')).toBeTruthy();
  });

  it('should_hide_unauthorized_actions_when_permission_missing', async () => {
    await renderPage([PERM.list]);
    const rowEl = await findRowByUsername('admin');
    expect(within(rowEl).queryByText(EDIT_BUTTON_TEXT)).toBeNull();
    expect(within(rowEl).queryByText(DELETE_BUTTON_TEXT)).toBeNull();
    expect(screen.queryByText(ADD_BUTTON_TEXT)).toBeNull();
  });
});
