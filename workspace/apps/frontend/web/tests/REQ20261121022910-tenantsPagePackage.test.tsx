/**
 * REQ-20261121-022910 租户管理页套餐扩展（TenantsPage）契约用例（AC-13）。
 *
 * 覆盖 AC-13 should_assign_package_on_tenants_page_when_created_or_edited：
 *   - 列表增「套餐」列：绑定租户直显套餐名（packageName 冗余/或选项解析双兼容），未绑定不误显；
 *   - 创建：套餐选择（可清空=不受限）→ POST body 携带 packageId；
 *   - 编辑：绑定租户套餐回显 → 清空 → PUT body packageId=null（解除绑定）；未绑定租户 → 选择 → PUT 携带 packageId。
 *
 * 装配照既有范本 web/tests/REQ20261117025200-tenantEnhance.test.tsx（fetch stubGlobal 精确
 * pathname 路由 + useMeStore 权限播种 + <AntApp> 包裹）＋ REQ20261120213603-menuFilter.test.tsx
 * （下拉交互防坑口径：mousedown 打 .ant-select-selector、选项限定浮层容器内精确匹配——pitfall 7/8）。
 *
 * 【表面假定（低风险，失败即交 TL 裁决）】
 *   A-1 表单 label 含「套餐」、placeholder 含「不受限」（tenants i18n 既有文案族；
 *       键名归 frontend，承载文案宽容匹配 pitfall 14）。
 *   A-2 套餐选项数据源 = GET /admin/v1/system/tenant-packages（契约确认⑦页面口径）；
 *       选项文案 = packageName（基础版/全功能版）。
 *   A-3 packageId wire：提交 number|null（core tenantCreate/tenantUpdate 载荷口径），
 *       断言 Number(body.packageId) 归一比较（数字/字符串 wire 双兼容）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { i18n, useAuthStore, useMeStore, type AdminTenantView } from '@acme/core';
import { TenantsPage } from '../src/pages/TenantsPage';

vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量（tenantEnhance 口径） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/; // 宽容匹配（autoInsertSpace 兼容，pitfall 10/14）
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const PACKAGE_LABEL = /套\s*餐/;
const PACKAGE_OPTION_A = '基础版';
const PACKAGE_OPTION_B = '全功能版';

const PERMS = ['system:tenant:list', 'system:tenant:create', 'system:tenant:update'];

const TENANT_ROWS: AdminTenantView[] = [
  {
    id: '3001', tenantCode: 'acme', tenantName: '爱思蜜科技', status: 'ENABLED', remark: '主租户',
    contactName: null, contactPhone: null, expiresAt: null,
    // REQ-20261121-022910：绑定「基础版」（packageName 由 handler/后端实时装配，此处直供视图形状）
    packageId: '1', packageName: PACKAGE_OPTION_A,
    createdAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    id: '3002', tenantCode: 'beta', tenantName: '贝塔租户', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: null,
    packageId: null, packageName: null,
    createdAt: '2026-01-02T00:00:00Z', version: '0',
  },
];

const PACKAGE_OPTIONS = [
  { id: '1', packageName: PACKAGE_OPTION_A },
  { id: '2', packageName: PACKAGE_OPTION_B },
];

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

const seedMe = async (): Promise<void> => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [],
  })));
  await useMeStore.getState().load();
};

interface TenantsState {
  posts: Record<string, unknown>[];
  puts: Record<string, unknown>[];
  rows: AdminTenantView[];
}

/** 租户 CRUD + 套餐分页 + me 精确 pathname 路由（伴随请求回空信封）。 */
const setupTenantsApi = () => {
  const state: TenantsState = {
    posts: [], puts: [],
    rows: TENANT_ROWS.map((row) => ({ ...row })),
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [] });
    }
    if (p === '/admin/v1/system/tenant-packages' && r.method === 'GET') {
      return envelope({ records: PACKAGE_OPTIONS, total: PACKAGE_OPTIONS.length });
    }
    if (p === '/admin/v1/system/tenants') {
      if (r.method === 'GET') return envelope({ records: state.rows, total: state.rows.length });
      if (r.method === 'POST') {
        const body = parseBody(r.body);
        state.posts.push(body);
        const created = {
          id: '9901', status: 'ENABLED', createdAt: '2026-06-01T00:00:00Z', version: '0',
          packageName: null, ...body,
        } as unknown as AdminTenantView;
        state.rows = [created, ...state.rows];
        return envelope(created);
      }
      return envelope(null);
    }
    if (/^\/admin\/v1\/system\/tenants\/[^/]+$/.test(p) && r.method === 'PUT') {
      const body = parseBody(r.body);
      state.puts.push(body);
      const id = p.split('/').filter(Boolean).pop() as string;
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

const renderPage = async (): Promise<{ state: TenantsState }> => {
  await seedMe();
  const api = setupTenantsApi();
  render(<AntApp><TenantsPage /></AntApp>);
  return api;
};

/** 下拉承载文案定位（menuFilter selectDescriptor 口径：placeholder/label/aria 兼容）。 */
const selectDescriptor = (select: Element): string => {
  const input = select.querySelector('input');
  const placeholderNode = select.querySelector('.ant-select-selection-placeholder');
  const labelText = input?.id
    ? document.querySelector<HTMLLabelElement>(`label[for="${input.id}"]`)?.textContent ?? null
    : null;
  return (
    input?.getAttribute('placeholder')
    ?? placeholderNode?.textContent
    ?? labelText
    ?? input?.getAttribute('aria-label')
    ?? select.getAttribute('aria-label')
    ?? ''
  ).trim();
};

const openSelectDropdown = (select: HTMLElement): void => {
  fireEvent.mouseDown(
    select.querySelector('.ant-select-selector')
    ?? select.querySelector('.ant-select-selection-search-input')
    ?? select,
  );
};

/** 选项点选：结构性遍历打开浮层内的 .ant-select-item-option（pitfall 7/8 口径，精确相等匹配）。 */
const pickOption = async (optionText: string): Promise<void> => {
  await waitFor(() => {
    expect(document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')).toBeTruthy();
  }, { timeout: 5000 });
  const dropdown = document.querySelector<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)') as HTMLElement;
  const target = Array.from(dropdown.querySelectorAll('.ant-select-item-option')).find(
    (option) => option.querySelector('.ant-select-item-option-content')?.textContent?.trim() === optionText,
  );
  if (!target) {
    throw new Error(`option not found: ${optionText}；实际选项=[${
      Array.from(dropdown.querySelectorAll('.ant-select-item-option'))
        .map((option) => option.querySelector('.ant-select-item-option-content')?.textContent?.trim())
        .join(' | ')}]`);
  }
  fireEvent.click(target);
};

const fillField = async (dialog: HTMLElement, label: RegExp, value: string): Promise<void> => {
  fireEvent.change(within(dialog).getByLabelText(label), { target: { value } });
};

const rowAction = async (anchor: string, action: RegExp): Promise<void> => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  const row = cell.closest('tr') as HTMLElement;
  expect(row, `行 ${anchor} 应存在`).toBeTruthy();
  fireEvent.click(within(row).getByText(action));
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

// ————— AC-13：租户页套餐列 + 套餐选择（可清空） —————

describe('REQ20261121022910 TenantsPage 套餐扩展（AC-13）', () => {
  it('should_assign_package_on_tenants_page_when_created_or_edited', async () => {
    const { state } = await renderPage();

    // —— 列表：套餐列直显绑定租户套餐名；未绑定不误显 ——
    await screen.findByText('爱思蜜科技', {}, { timeout: 5000 });
    const boundRow = screen.getByText('爱思蜜科技').closest('tr') as HTMLElement;
    expect(within(boundRow).getByText(PACKAGE_OPTION_A), '绑定租户套餐名直显').toBeTruthy();
    const freeRow = screen.getByText('贝塔租户').closest('tr') as HTMLElement;
    expect(within(freeRow).queryByText(PACKAGE_OPTION_A),
      '未绑定租户不得误显他人套餐').toBeNull();

    // —— 创建：套餐选择「基础版」→ POST body 携带 packageId=1 ——
    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const createDialog = await screen.findByRole('dialog');
    await fillField(createDialog, /租\s*户\s*代\s*码/, 'xin-zu-hu-it');
    await fillField(createDialog, /租\s*户\s*名\s*称/, '新租户甲');
    const createSelect = Array.from(createDialog.querySelectorAll('.ant-select'))
      .find((select) => PACKAGE_LABEL.test(selectDescriptor(select))) as HTMLElement;
    expect(createSelect, '创建弹窗应含套餐选择下拉').toBeTruthy();
    openSelectDropdown(createSelect);
    await pickOption(PACKAGE_OPTION_A);
    await waitFor(() => {
      expect(createSelect.querySelector('.ant-select-selection-item')?.textContent).toBe(PACKAGE_OPTION_A);
    });
    fireEvent.click(await within(createDialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.posts).toHaveLength(1));
    expect(state.posts[0]?.tenantCode).toBe('xin-zu-hu-it');
    expect(Number(state.posts[0]?.packageId),
      '创建提交应携带所选套餐 id（number|null 载荷口径）').toBe(1);
    await screen.findByText(/已创建/, {}, { timeout: 5000 });

    // —— 编辑（已绑定租户）：套餐回显「基础版」→ 清空 → PUT packageId=null ——
    await rowAction('爱思蜜科技', EDIT_BUTTON);
    const editDialog = await screen.findByRole('dialog');
    const editSelect = Array.from(editDialog.querySelectorAll('.ant-select'))
      .find((select) => PACKAGE_LABEL.test(selectDescriptor(select))) as HTMLElement;
    await waitFor(() => {
      expect(editSelect.querySelector('.ant-select-selection-item')?.textContent,
        '已绑定套餐应回显').toBe(PACKAGE_OPTION_A);
    });
    // 清空（allowClear）：hover 显示清除按钮 → mousedown+click 触发（rc-select clear 走 onMouseDown）
    fireEvent.mouseEnter(editSelect);
    const clearIcon = await waitFor(() => {
      const el = editSelect.querySelector('.ant-select-clear');
      expect(el).toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.mouseDown(clearIcon);
    fireEvent.click(clearIcon);
    await waitFor(() => {
      expect(editSelect.querySelector('.ant-select-selection-item')).toBeNull();
    });
    fireEvent.click(await within(editDialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.puts).toHaveLength(1));
    expect(state.puts[0]?.packageId,
      '清空=解除绑定（null 提交）').toBeNull();
    expect(state.puts[0]?.version).toBe('0');
    await screen.findByText(/已更新/, {}, { timeout: 5000 });

    // —— 编辑（未绑定租户）：选择「全功能版」→ PUT 携带 packageId=2 ——
    await rowAction('贝塔租户', EDIT_BUTTON);
    const editDialog2 = await screen.findByRole('dialog');
    const editSelect2 = Array.from(editDialog2.querySelectorAll('.ant-select'))
      .find((select) => PACKAGE_LABEL.test(selectDescriptor(select))) as HTMLElement;
    expect(editSelect2.querySelector('.ant-select-selection-item'),
      '未绑定租户套餐初始为空').toBeNull();
    openSelectDropdown(editSelect2);
    await pickOption(PACKAGE_OPTION_B);
    fireEvent.click(await within(editDialog2).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.puts).toHaveLength(2));
    expect(Number(state.puts[1]?.packageId),
      '编辑分配应提交所选套餐 id').toBe(2);
  });

  it('should_render_package_column_header_when_page_loaded', async () => {
    // 列头独立断言（AC-13 列表面拆分）：表头含「套餐」列且两个样例行渲染
    await renderPage();
    await screen.findByText('爱思蜜科技', {}, { timeout: 5000 });
    const headerText = (document.querySelector('.ant-table-thead') as HTMLElement)?.textContent ?? '';
    expect(headerText, '表头应含套餐列').toContain('套餐');
    expect(screen.getByText('贝塔租户')).toBeTruthy();
  });
});
