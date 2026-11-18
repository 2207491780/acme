/**
 * REQ-20261114-120104 AC-8（前端面·岗位页）：PositionsPage 列表分页/新建/编辑回填/
 * 删除（POSITION_IN_USE 反馈）/权限显隐（fail-closed）。
 *
 * 装配照既有范本 web/tests/REQ20261114120102-noticesPage.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 三段式播种 + <AntApp> 包裹 + testTimeout ≥15000ms + 关闭态断言数据驱动；
 * CRUD 交互口径对齐 REQ20261114120101-crudPages（label 式填表 + Select 显示名点选 + 关闭态数据驱动）。
 *
 * 【表面假定（REQ-20261114-120104 系列 A-1..，汇总见交付说明；待 TL 裁决）】
 *   A-1 页面 web/src/pages/PositionsPage.tsx 命名导出 PositionsPage，自取数据零必填 props（crudPages A-35 口径）。
 *   A-6 PositionView wire 形状：id/positionCode/positionName/status/createdAt/version（roleCode/roleName
 *       同构、camelCase、id 字符串）；GET /admin/v1/system/positions 分页 {records,total}，列表请求携带
 *       page=1（crudPages A-33 口径）。
 *   A-7 表单字段 label：「岗位名称」「岗位编码」（Input 必填）；状态 Select 不交互（显示名未知不猜，
 *       假定默认值可直接提交）。
 *   A-8 POSITION_IN_USE 转译文案未知 → 宽松正则 /引用|POSITION_IN_USE/（filesPage A-29 口径），
 *       断言收敛 .ant-message 容器。
 *   A-15 下拉点选收敛：经开放下拉容器（.ant-select-dropdown:not(.ant-select-dropdown-hidden)）
 *       within().findByText(显示名) 点选（委派单口径、禁 role=option 探针）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { PositionsPage } from '../src/pages/PositionsPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量（crudPages 口径：两字按钮 autoInsertSpace 兼容） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const DELETE_BUTTON = /删\s*除/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const MSG_IN_USE = /引用|POSITION_IN_USE/; // A-8

const PERM = {
  list: ['system:position:list'],
  create: ['system:position:create'],
  update: ['system:position:update'],
  delete: ['system:position:delete'],
};

const POSITIONS_PATH_PART = '/positions';

/**
 * fixtures（A-6 wire 形状）：前端工程师(5001) mock 为 POSITION_IN_USE 拒删目标；
 * 测试工程师(5003) 为可删行。
 */
const POSITION_ROWS: Record<string, unknown>[] = [
  { id: '5001', positionCode: 'frontend_eng', positionName: '前端工程师', status: 'ENABLED', createdAt: '2026-01-01T00:00:00Z', version: '0' },
  { id: '5002', positionCode: 'product_mgr', positionName: '产品经理', status: 'ENABLED', createdAt: '2026-01-02T00:00:00Z', version: '0' },
  { id: '5003', positionCode: 'qa_eng', positionName: '测试工程师', status: 'ENABLED', createdAt: '2026-01-03T00:00:00Z', version: '0' },
];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string) => new Response(
  JSON.stringify({ success: false, code, message }), { status: 200 },
);

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

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

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(POSITIONS_PATH_PART) && r.method === method);

const lastQuery = (fetchMock: ReturnType<typeof vi.fn>) =>
  callsOf(fetchMock, 'GET').at(-1)?.url.searchParams ?? null;

const setupPositionsApi = () => {
  const state = { rows: POSITION_ROWS.map((row) => ({ ...row })) };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (!p.includes(POSITIONS_PATH_PART)) return envelope(null);
    if (r.method === 'GET') return envelope({ records: state.rows, total: state.rows.length });
    if (r.method === 'POST') {
      const body = parseBody(r.body);
      const created = { id: '9901', status: 'ENABLED', version: '0', ...body };
      state.rows = [...state.rows, created];
      return envelope(created);
    }
    if (r.method === 'PUT') {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      const body = parseBody(r.body);
      state.rows = state.rows.map((row) => (row.id === id ? { ...row, ...body } : row));
      return envelope(state.rows.find((row) => row.id === id) ?? null);
    }
    if (r.method === 'DELETE') {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      // 被用户引用的岗位删除拒绝（契约 §API 错误码 POSITION_IN_USE）
      if (id === '5001') return failureEnvelope('POSITION_IN_USE', '岗位已被用户引用，无法删除');
      state.rows = state.rows.filter((row) => row.id !== id);
      return envelope(null);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

// ————— 装配 —————
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

const renderPage = async (permissions: string[]) => {
  await seedMe(permissions);
  const api = setupPositionsApi();
  render(<AntApp><PositionsPage /></AntApp>);
  return api;
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

/** 对话框内文本类输入框（Input/TextArea/number；排除 select 搜索框/checkbox/hidden，A-36 口径） */
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

describe('REQ20261114120104 岗位管理页 PositionsPage（AC-8·岗位页）', () => {
  it('should_render_position_list_with_pagination_when_page_loads', async () => {
    const { fetchMock } = await renderPage(PERM.list);

    expect(await screen.findByText('前端工程师')).toBeTruthy();
    expect(screen.getByText('产品经理')).toBeTruthy();
    // 列表请求携带分页参数（A-6，CrudTable page=1 口径）
    expect(lastQuery(fetchMock)?.get('page')).toBe('1');
  });

  it('should_submit_create_with_name_and_code_when_add_confirmed', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.create]);
    await screen.findByText('前端工程师');

    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('岗位名称'), { target: { value: '人事专员' } });
    fireEvent.change(within(dialog).getByLabelText('岗位编码'), { target: { value: 'xin-gang-wei-1' } });
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const posts = callsOf(fetchMock, 'POST');
      expect(posts).toHaveLength(1);
      const bodyJson = JSON.stringify(parseBody(posts[0]?.body));
      expect(bodyJson).toContain('人事专员');
      expect(bodyJson).toContain('xin-gang-wei-1');
    });
    // 关闭态数据驱动：提交成功后的 reload 二次 GET（071607 裁决⑪）
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET').length).toBeGreaterThanOrEqual(2);
    });
  });

  it('should_prefill_and_submit_update_when_row_edit_clicked', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.update]);
    const rowEl = await findRowByText('产品经理');

    fireEvent.click(await within(rowEl).findByText(EDIT_BUTTON));
    const dialog = await screen.findByRole('dialog');
    const box = await waitFor(() => {
      const found = dialogTextboxes(dialog).find((el) => el.value === '产品经理');
      expect(found).toBeTruthy();
      return found as HTMLInputElement | HTMLTextAreaElement;
    });
    fireEvent.change(box, { target: { value: '产品经理改名' } });
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const updates = callsOf(fetchMock, 'PUT');
      expect(updates).toHaveLength(1);
      expect(updates[0]?.url.pathname).toContain('5002');
      expect(JSON.stringify(parseBody(updates[0]?.body))).toContain('产品经理改名');
    });
  });

  it('should_delete_unrefenced_position_after_confirm_when_row_delete_clicked', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.delete]);
    const rowEl = await findRowByText('测试工程师'); // 无引用行可直接删

    fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const deletes = callsOf(fetchMock, 'DELETE');
      expect(deletes).toHaveLength(1);
      expect(deletes[0]?.url.pathname).toContain('5003');
    });
    await waitFor(() => expect(screen.queryByText('测试工程师')).toBeNull());
    expect(screen.getByText('前端工程师')).toBeTruthy();
  });

  it('should_keep_row_and_show_translated_feedback_when_position_in_use_delete_rejected', async () => {
    const { fetchMock } = await renderPage([...PERM.list, ...PERM.delete]);
    const rowEl = await findRowByText('前端工程师'); // 被引用行 → POSITION_IN_USE

    fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(callsOf(fetchMock, 'DELETE')).toHaveLength(1));
    // 统一转译反馈（文案未知 → 宽松正则，A-8）：收敛 antd message 容器
    await waitFor(() => {
      expect(document.querySelector('.ant-message')?.textContent ?? '').toMatch(MSG_IN_USE);
    });
    // 行保留（数据驱动：未删除）
    expect(screen.getByText('前端工程师')).toBeTruthy();
  });

  it('should_hide_unauthorized_actions_when_position_permissions_missing', async () => {
    await renderPage(PERM.list); // 仅 list 权限（fail-closed）
    await screen.findByText('前端工程师');

    expect(screen.queryByText(ADD_BUTTON)).toBeNull();
    const rowEl = await findRowByText('前端工程师');
    expect(within(rowEl).queryByText(EDIT_BUTTON)).toBeNull();
    expect(within(rowEl).queryByText(DELETE_BUTTON)).toBeNull();
  });
});
