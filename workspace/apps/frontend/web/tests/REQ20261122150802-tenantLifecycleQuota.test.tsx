/**
 * REQ-20261122-150802（TENANT-4）TenantsPage 租户生命周期/配额面契约用例（AC-6）。
 *
 * 覆盖 AC-6（确认记录②⑧）：
 *   - should_render_four_lifecycle_tags_when_status_and_derived_inputs_combined：
 *     四视觉态 Tag（启用中/宽限期/已过期/已停用——status+派生合成）；已停用正交优先
 *     （status=DISABLED 且派生宽限期内 → 仍显示已停用，不显示宽限期）；
 *   - should_show_usage_aggregate_when_usage_action_clicked：操作列「用量」按钮 →
 *     用量 Modal 内 storageBytes/userCount/配额值/来源层级（来源层级=平台缺省演示）；
 *   - should_put_override_then_clear_inheritance_when_quota_modal_used：配额管理 Modal
 *     PUT 覆盖 + 清除继承（空值提交=null 回落）；
 *   - should_submit_grace_until_when_editing_tenant：租户编辑表单 graceUntil 字段
 *     （DatePicker「宽限期至」）编辑提交携带（Dayjs→ISO）。
 *
 * 装配照既有范本 web/tests/REQ20261122102954-tenantHierarchy.test.tsx（fetch stubGlobal
 * 精确 pathname 路由 + useMeStore 权限播种 + <AntApp> 包裹 + i18n zh-CN）＋
 * REQ20261117025200-tenantEnhance.test.tsx（DatePicker focus→change→Enter jsdom 口径）。
 * 日期样例全部动态生成（相对 now），不依赖 fixture 钉死日期。
 *
 * 【页面实测事实（self-verify 迭代确认，替代首轮假定）】
 *   F-1 四态文案=契约⑧钉死词：启用中/宽限期/已过期/已停用（列内 Tag，pitfall 14 空白宽容）。
 *   F-2 操作列=直显按钮「编辑/用量/配额/停用」（无更多下拉）；autoInsertSpace 空白宽容匹配。
 *   F-3 用量 Modal：含存储/用户/配额/来源层级字段族，来源值中文映射（平台缺省/租户覆盖等）。
 *   F-4 配额 Modal（标题「配额管理：<tenantCode>」）：存储配额覆盖（GiB）输入，wire=值×1GiB 字节；
 *       「留空提交=清除租户覆盖」——清除=空值提交，无独立清除按钮；文案含当前有效配额/来源。
 *   F-5 宽限字段「宽限期至」位于编辑弹窗（创建弹窗无此字段）——确认⑥「管理动作=update 收 graceUntil」；
 *       DatePicker label 未与内层 input 建 for 关联 → 经 .ant-form-item 邻近定位。
 *   F-6 表格列含「宽限期至」列（表头）；列表加载伴随 GET /tenant-packages（套餐名解析面）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import dayjs from 'dayjs';
import { i18n, useAuthStore, useMeStore, type AdminTenantView } from '@acme/core';
import { TenantsPage } from '../src/pages/TenantsPage';

vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量（tenantHierarchy / tenantEnhance 口径） —————
const EDIT_BUTTON = /编\s*辑/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const USAGE_ACTION = /用\s*量/;
const QUOTA_ACTION = /配\s*额/;
const GRACE_LABEL = /宽\s*限/;

const PERMS = ['system:tenant:list', 'system:tenant:create', 'system:tenant:update'];

/** 动态派生日期（相对 now：宽限未来/过期双向样例，不依赖钉死日期）。 */
const iso = (offsetMs: number): string => new Date(Date.now() + offsetMs).toISOString();
const PAST_DAY = iso(-30 * 24 * 3600_000);
const PAST_MONTH = iso(-60 * 24 * 3600_000);
const FUTURE_MONTH = iso(30 * 24 * 3600_000);
const FUTURE_TWO_MONTHS = iso(60 * 24 * 3600_000);

/** 四态样例行（id/tenantName 唯一锚定；usage/quota 值见 usageOf/row 初始 quotaStorageBytes）。 */
const TENANT_ROWS: AdminTenantView[] = [
  {
    // 启用中：无有效期无宽限（expires 未到或无 expires → 启用中）
    id: '7101', tenantCode: 'lifecycle-a', tenantName: '苍梧云科技', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: null, graceUntil: null,
    packageId: null, packageName: null, parentId: null, level: 1,
    quotaStorageBytes: 1_073_741_824,
    createdAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    // 宽限期：expires 已过且 grace 未到（仍可登录）
    id: '7102', tenantCode: 'lifecycle-g', tenantName: '宽限样本租户', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: PAST_DAY, graceUntil: FUTURE_MONTH,
    packageId: null, packageName: null, parentId: null, level: 1,
    quotaStorageBytes: 1_073_741_824,
    createdAt: '2026-01-02T00:00:00Z', version: '0',
  },
  {
    // 已过期：expires 与 grace 均已过
    id: '7103', tenantCode: 'lifecycle-e', tenantName: '逾期样本租户', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: PAST_MONTH, graceUntil: PAST_DAY,
    packageId: null, packageName: null, parentId: null, level: 1,
    quotaStorageBytes: 3_221_225_472,
    createdAt: '2026-01-03T00:00:00Z', version: '0',
  },
  {
    // 已停用正交优先：status=DISABLED 且派生宽限期内 → 仍显示已停用
    id: '7104', tenantCode: 'lifecycle-d', tenantName: '停用样本租户', status: 'DISABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: PAST_DAY, graceUntil: FUTURE_TWO_MONTHS,
    packageId: null, packageName: null, parentId: null, level: 1,
    quotaStorageBytes: 1_073_741_824,
    createdAt: '2026-01-04T00:00:00Z', version: '0',
  },
];

/** usage 端点演示值（r1 平台缺省 / r3 租户覆盖来源演示）。 */
const usageOf = (id: string): Record<string, unknown> => {
  const seeds: Record<string, Record<string, unknown>> = {
    '7101': { storageBytes: 268_435_456, userCount: 12, quotaStorageBytes: 1_073_741_824, quotaSource: 'PLATFORM_DEFAULT' },
    '7102': { storageBytes: 52_428_800, userCount: 7, quotaStorageBytes: 1_073_741_824, quotaSource: 'PLATFORM_DEFAULT' },
    '7103': { storageBytes: 1_048_576, userCount: 2, quotaStorageBytes: 3_221_225_472, quotaSource: 'TENANT_OVERRIDE' },
    '7104': { storageBytes: 0, userCount: 1, quotaStorageBytes: 1_073_741_824, quotaSource: 'PLATFORM_DEFAULT' },
  };
  return { ...(seeds[id] ?? { storageBytes: 0, userCount: 0, quotaStorageBytes: 1_073_741_824, quotaSource: 'PLATFORM_DEFAULT' }) };
};

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const asRequest = (input: RequestInfo | URL, init?: RequestInit): { url: URL; method: string; body: BodyInit | null | undefined } => {
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

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

interface TenantsLifecycleState {
  rows: AdminTenantView[];
  posts: Record<string, unknown>[];
  puts: Record<string, unknown>[];
  statusPuts: Array<{ id: string; body: Record<string, unknown> }>;
  quotaPuts: Array<{ id: string; body: Record<string, unknown> }>;
  usageGets: string[];
}

/** 租户 CRUD/启停/update + usage/quota 端点精确 pathname 路由（伴随请求回空信封）。 */
const setupTenantsApi = () => {
  const state: TenantsLifecycleState = {
    rows: TENANT_ROWS.map((row) => ({ ...row })),
    posts: [], puts: [], statusPuts: [], quotaPuts: [], usageGets: [],
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [] });
    }
    if (p === '/admin/v1/system/tenants/tree' && r.method === 'GET') {
      const tops = state.rows.filter((row) => row.parentId == null);
      const tree = tops.map((top) => ({
        ...top,
        children: state.rows.filter((row) => row.parentId === top.id).map((child) => ({ ...child, children: [] })),
      }));
      return envelope(tree);
    }
    if (p === '/admin/v1/system/tenants') {
      if (r.method === 'GET') return envelope({ records: state.rows, total: state.rows.length });
      if (r.method === 'POST') {
        const body = parseBody(r.body);
        state.posts.push(body);
        const created = {
          id: '9901', status: 'ENABLED', createdAt: '2026-06-01T00:00:00Z', version: '0',
          packageId: null, packageName: null, parentId: null, level: 1,
          quotaStorageBytes: 1_073_741_824, ...body,
        } as unknown as AdminTenantView;
        state.rows = [created, ...state.rows];
        return envelope(created);
      }
      return envelope(null);
    }
    const tenantPath = /^\/admin\/v1\/system\/tenants\/([^/]+)(?:\/([a-z]+))?$/.exec(p);
    if (tenantPath && r.method === 'GET' && tenantPath[2] === 'usage') {
      state.usageGets.push(tenantPath[1] as string);
      return envelope(usageOf(tenantPath[1] as string));
    }
    if (tenantPath && r.method === 'PUT' && tenantPath[2] === 'quota') {
      const body = parseBody(r.body);
      const id = tenantPath[1] as string;
      state.quotaPuts.push({ id, body });
      const target = state.rows.find((item) => item.id === id);
      if (!target) return envelope(null);
      const updated = { ...target, quotaStorageBytes: body.quotaStorageBytes ?? target.quotaStorageBytes } as AdminTenantView;
      state.rows = state.rows.map((item) => (item.id === id ? updated : item));
      return envelope(updated);
    }
    if (tenantPath && r.method === 'PUT' && tenantPath[2] === 'status') {
      const body = parseBody(r.body);
      const id = tenantPath[1] as string;
      state.statusPuts.push({ id, body });
      state.rows = state.rows.map((item) => (item.id === id ? { ...item, ...body } as AdminTenantView : item));
      return envelope(null);
    }
    if (tenantPath && r.method === 'PUT' && !tenantPath[2]) {
      const body = parseBody(r.body);
      const id = tenantPath[1] as string;
      state.puts.push(body);
      const target = state.rows.find((item) => item.id === id);
      if (!target) return envelope(null);
      const updated = { ...target, ...body } as AdminTenantView;
      state.rows = state.rows.map((item) => (item.id === id ? updated : item));
      return envelope(updated);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const renderPage = async (): Promise<{ state: TenantsLifecycleState }> => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [],
  })));
  await useMeStore.getState().load();
  const api = setupTenantsApi();
  render(<AntApp><TenantsPage /></AntApp>);
  return api;
};

/** 行锚定（tenantHierarchy 口径）：按租户名找行。 */
const rowByAnchor = async (anchor: string): Promise<HTMLElement> => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  const row = cell.closest('tr') as HTMLElement;
  expect(row, `行 ${anchor} 应存在`).toBeTruthy();
  return row;
};

/** DatePicker 输入提交（tenantEnhance 口径：focus → change → Enter 提交解析值）。
 *  宽限字段 label 未与 picker 内层 input 建 for 关联（实测）→ 经 .ant-form-item 邻近定位。 */
const fillDatePicker = async (dialog: HTMLElement, label: RegExp, value: string): Promise<void> => {
  const labelNode = Array.from(dialog.querySelectorAll('label'))
    .find((node) => label.test(node.textContent ?? ''));
  expect(labelNode, `表单应含 ${label} 字段 label（A-5）`).toBeTruthy();
  const item = (labelNode as HTMLElement).closest('.ant-form-item') as HTMLElement;
  const input = item?.querySelector<HTMLInputElement>('.ant-picker input, input.ant-input') ?? null;
  expect(input, `表单 ${label} 字段应含可输入控件`).toBeTruthy();
  fireEvent.focus(input as HTMLInputElement);
  fireEvent.change(input as HTMLInputElement, { target: { value } });
  fireEvent.keyDown(input as HTMLInputElement, { key: 'Enter', keyCode: 13, which: 13 });
};

/**
 * 触发操作列入口（A-2 宽容）：优先行内直显按钮；退回「更多」下拉项（portal 浮层内点选）。
 * 找不到即显式失败（不静默吞，交 TL 裁决页形态）。
 */
const openRowAction = async (anchor: string, action: RegExp): Promise<void> => {
  const row = await rowByAnchor(anchor);
  const direct = within(row).queryByText(action);
  if (direct) {
    fireEvent.click(direct);
    return;
  }
  const more = within(row).queryByText(/更\s*多|操作|…|\.\.\./);
  if (more) {
    fireEvent.click(more);
    await waitFor(() => {
      expect(document.querySelector('.ant-dropdown:not(.ant-dropdown-hidden)')).toBeTruthy();
    }, { timeout: 5000 });
    const overlay = document.querySelector<HTMLElement>('.ant-dropdown:not(.ant-dropdown-hidden)') as HTMLElement;
    const item = Array.from(overlay.querySelectorAll('li, .ant-dropdown-menu-item'))
      .find((node) => action.test(node.textContent ?? ''));
    expect(item, `「更多」下拉内应含 ${action} 项`).toBeTruthy();
    fireEvent.click(item as HTMLElement);
    return;
  }
  throw new Error(`行 ${anchor} 操作入口未找到（直显/更多双形态均未命中，A-2 假定破）`);
};

/** 可见 Modal 表面（过滤已关闭但未卸载的 antd Modal 残留节点）。 */
const visibleModals = (keyword: RegExp): HTMLElement[] =>
  Array.from(document.querySelectorAll<HTMLElement>('.ant-modal-content, .ant-drawer-content'))
    .filter((node) => keyword.test(node.textContent ?? ''))
    .filter((node) => {
      const wrap = node.closest('.ant-modal-wrap') as HTMLElement | null;
      return !wrap || wrap.style.display !== 'none';
    });

/** Modal 打开等待（用量/配额 Modal 双形态宽容：antd Modal 或 Drawer 承载）。 */
const awaitSurface = async (keyword: RegExp): Promise<HTMLElement> => {
  const surface = await waitFor(() => {
    const hit = visibleModals(keyword)[0];
    expect(hit, `应弹出含「${keyword}」的 Modal/Drawer`).toBeTruthy();
    return hit as HTMLElement;
  }, { timeout: 5000 });
  return surface;
};

beforeEach(async () => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  await i18n.changeLanguage('zh-CN');
  stubBrowserGaps();
});

afterEach(async () => {
  cleanup();
  vi.unstubAllGlobals();
  await i18n.changeLanguage('zh-CN');
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— AC-6：四视觉态 Tag / 用量 Modal / 配额 Modal / graceUntil 表单 —————

describe('REQ20261122150802 TenantsPage 生命周期/配额面（AC-6）', () => {
  it('should_render_four_lifecycle_tags_when_status_and_derived_inputs_combined', async () => {
    await renderPage();
    await screen.findByText('苍梧云科技', {}, { timeout: 5000 });
    expect(screen.getByText('宽限样本租户')).toBeTruthy();
    expect(screen.getByText('逾期样本租户')).toBeTruthy();
    expect(screen.getByText('停用样本租户')).toBeTruthy();

    const rowOf = async (anchor: string): Promise<HTMLElement> => rowByAnchor(anchor);

    // 启用中：无有效期无宽限（expires 未到或无 expires → 启用中）
    const activeRow = await rowOf('苍梧云科技');
    expect(within(activeRow).getAllByText(/启\s*用\s*中/).length,
      '无有效期租户应显示「启用中」Tag（A-1）').toBeGreaterThan(0);

    // 宽限期：expires 已过且 grace 未到
    const graceRow = await rowOf('宽限样本租户');
    expect(within(graceRow).getAllByText(/宽\s*限\s*期/).length,
      '宽限期内租户应显示「宽限期」Tag（确认②派生态）').toBeGreaterThan(0);

    // 已过期：expires 与 grace 均过
    const expiredRow = await rowOf('逾期样本租户');
    expect(within(expiredRow).getAllByText(/已\s*过\s*期/).length,
      '宽限也过租户应显示「已过期」Tag').toBeGreaterThan(0);

    // 已停用正交优先：DISABLED 且派生宽限期内 → 已停用（不显示宽限期）
    const disabledRow = await rowOf('停用样本租户');
    expect(within(disabledRow).getAllByText(/已\s*停\s*用/).length,
      '停用租户应显示「已停用」Tag（status 正交优先，A-1）').toBeGreaterThan(0);
    expect(within(disabledRow).queryAllByText(/宽\s*限\s*期/).length,
      '停用租户即使宽限期内也不显示「宽限期」（确认②正交优先）').toBe(0);
  });

  it('should_show_usage_aggregate_when_usage_action_clicked', async () => {
    await renderPage();
    await screen.findByText('苍梧云科技', {}, { timeout: 5000 });

    // 操作列「用量」→ 用量 Modal（A-2 入口宽容）
    await openRowAction('苍梧云科技', USAGE_ACTION);
    const surface = await awaitSurface(/用\s*量/);
    const text = surface.textContent ?? '';

    // 用量 Modal 字段族（A-3 宽容）：存储/用户/配额/来源层级 + 来源值=平台缺省演示
    expect(text, '用量 Modal 应含存储用量字段').toMatch(/存\s*储|storage/i);
    expect(text, '用量 Modal 应含用户数字段').toMatch(/用\s*户|user/i);
    expect(text, '用量 Modal 应含配额字段').toMatch(/配\s*额|quota/i);
    expect(text, '用量 Modal 应含来源层级字段（确认⑧）').toMatch(/来\s*源|层\s*级/);
    expect(text, '用量 Modal 来源层级值应含平台缺省演示（A-3）')
      .toMatch(/平\s*台\s*缺\s*省|平\s*台\s*默\s*认|PLATFORM_DEFAULT/i);

    // userCount=12 演示值出现在 Modal 文本中（宽容：千分位/裸值双形态）
    expect(text, '用量 Modal 应呈现 userCount 演示值（12）').toMatch(/(^|[^\d])12([^\d]|$)/);
  });

  it('should_put_override_then_clear_inheritance_when_quota_modal_used', async () => {
    const { state } = await renderPage();
    await screen.findByText('宽限样本租户', {}, { timeout: 5000 });

    // 配额管理 Modal（A-2 入口宽容）
    await openRowAction('宽限样本租户', QUOTA_ACTION);
    const surface = await awaitSurface(/配\s*额/);

    // 覆盖：填 12345 → 确定 → PUT /tenants/{id}/quota（wire=字节口径）。
    // 实测 Modal 输入单位=GiB（表单值×1GiB→wire 字节，12345×1073741824），断言换算后字节值。
    const input = surface.querySelector<HTMLInputElement>('input.ant-input-number-input, input[type="number"], input.ant-input');
    expect(input, '配额 Modal 应含数值输入（A-4）').toBeTruthy();
    fireEvent.change(input as HTMLInputElement, { target: { value: '12345' } });
    fireEvent.click(await within(surface).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.quotaPuts.length).toBeGreaterThan(0));
    const overridePut = state.quotaPuts[0] as { id: string; body: Record<string, unknown> };
    expect(overridePut.id).toBe('7102');
    expect(Number(overridePut.body.quotaStorageBytes),
      'PUT 覆盖 wire=字节口径（表单 12345 GiB → 12345×1GiB）').toBe(12345 * 1_073_741_824);

    // 清除继承：重开 Modal → 留空输入提交=清除租户覆盖（实测 Modal 文案「留空提交=清除租户覆盖」，
    // 无独立清除按钮——A-4 修正为空值提交语义）→ PUT body.quotaStorageBytes=null
    await openRowAction('宽限样本租户', QUOTA_ACTION);
    const surface2 = await awaitSurface(/配\s*额/);
    const input2 = surface2.querySelector<HTMLInputElement>('input.ant-input-number-input, input[type="number"], input.ant-input');
    expect(input2, '重开的配额 Modal 应含数值输入').toBeTruthy();
    fireEvent.change(input2 as HTMLInputElement, { target: { value: '' } });
    fireEvent.click(await within(surface2).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.quotaPuts.length, '清除继承应触发第二次 PUT').toBeGreaterThanOrEqual(2), { timeout: 5000 });
    const clearPut = state.quotaPuts[1] as { id: string; body: Record<string, unknown> };
    expect(clearPut.id).toBe('7102');
    expect(clearPut.body.quotaStorageBytes ?? null, '清除继承应提交 null（回落下一级）').toBeNull();
  });

  it('should_submit_grace_until_when_editing_tenant', async () => {
    const { state } = await renderPage();
    await screen.findByText('宽限样本租户', {}, { timeout: 5000 });

    // 编辑流（实测：宽限期至字段位于编辑弹窗、创建弹窗无此字段——确认⑥「管理动作=update 收 graceUntil」语义）
    const row = (screen.getByText('宽限样本租户') as HTMLElement).closest('tr') as HTMLElement;
    fireEvent.click(within(row).getByText(EDIT_BUTTON));
    const dialog = await screen.findByRole('dialog');

    // 宽限字段（A-5 label 宽容：宽限期至）：DatePicker focus → change → Enter（Dayjs→ISO）
    await fillDatePicker(dialog, GRACE_LABEL, '2027-06-30');
    fireEvent.click(await within(dialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.puts).toHaveLength(1));
    const body = state.puts[0] ?? {};
    // update 请求收 graceUntil（确认⑥）：Dayjs → ISO-8601 字符串（所选日期语义一致）
    expect(typeof body.graceUntil, '编辑提交应携带 graceUntil（确认⑥ update 收宽限）').toBe('string');
    expect(dayjs(String(body.graceUntil)).format('YYYY-MM-DD')).toBe('2027-06-30');
  });
});
