/**
 * REQ-20261122-102954 子租户层级（TENANT-3）TenantsPage 层级面契约用例（AC-9）。
 *
 * 覆盖 AC-9（确认记录⑩：列表层级列 + 新建/编辑表单 parent 下拉排除自身与后代防环
 * + 停用级联提示文案）：
 *   - should_render_hierarchy_column_when_list_loaded：列表层级列（缩进树形或 parent 列双形态宽容）；
 *   - should_exclude_self_and_descendants_from_parent_options_when_editing：父下拉排除自身/后代；
 *   - should_warn_cascade_and_submit_status_when_disabling_tenant_with_children：停用含子租户的
 *     父租户出现级联提示 → 确认后 PUT /tenants/{id}/status（AC-10 status 同形面）；
 *   - should_submit_parentId_when_creating_child_tenant：创建携 parentId / 未选父提交 null（顶层）。
 *
 * REQ-20261125-230807 delta-r3 基线更新（AC-22）：新建必选模板+初始管理员且提交改走
 * POST /admin/v1/system/tenants/init（tenantCreate 不再被前端调用）；原用例未填模板被表单
 * 拦截 → posts 空。本用例保持 parentId 断言不变（init 载荷仍携 parentId），补填模板/管理员。
 *
 * 装配照既有范本 web/tests/REQ20261121022910-tenantsPagePackage.test.tsx（fetch stubGlobal
 * 精确 pathname 路由 + useMeStore 权限播种 + <AntApp> 包裹 + 下拉防坑口径：mousedown 打
 * .ant-select-selector、选项限定浮层容器内匹配——pitfall 7/8/10/14）。
 *
 * 【表面假定（低风险，失败即交 TL 裁决）】
 *   A-1 层级列表形态宽容（契约⑩「level 缩进树形或 parent 列」）：表头含
 *       层级/上级/父级任一；子租户行含父名或层级数字标识。
 *   A-2 父下拉 label 宽容：上级/父级/父租户/parent 任一命中（tenants i18n 文案族，
 *       pitfall 14 宽出口径）。
 *   A-3 parentId wire=字符串（core tenantCreate 载荷口径 parentId?: string | null）；
 *       未选父=null 提交。
 *   A-4 status wire：PUT /tenants/{id}/status body { status: 'DISABLED' }（EnableStatus 域）。
 *   A-5 停用入口形态宽容：行内「停用」按钮或 Switch（既有页形态未钉死）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { i18n, useAuthStore, useMeStore, type AdminTenantView } from '@acme/core';
import { TenantsPage } from '../src/pages/TenantsPage';

vi.setConfig({ testTimeout: 90000 }); // delta-r3：同机后端全量测试 CPU 争抢下 25s 饥饿超时，对齐新套件显式超时口径

// ————— 行为常量（tenantsPagePackage / tenantEnhance 口径） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/; // 宽容匹配（autoInsertSpace 兼容，pitfall 10/14）
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const CANCEL_BUTTON = /取\s*消|^Cancel$/i;
const PARENT_LABEL = /上\s*级|父\s*级|父\s*租\s*户|parent/i;
const TEMPLATE_LABEL = /开\s*通\s*模\s*板/;

// 开通模板目录 fixture（REQ-20261125-230807 AC-22：新建模板必选；选项源 tenant-templates）
const TEMPLATE_ROWS: Record<string, unknown>[] = [
  { id: '8101', code: 'std', name: '标准开通模板', status: 'ENABLED', createdAt: '2026-01-01T00:00:00Z', version: '0', content: { rolePresets: [], configEntries: [] } },
];

const PERMS = ['system:tenant:list', 'system:tenant:create', 'system:tenant:update'];

const TENANT_ROWS: AdminTenantView[] = [
  {
    id: '3001', tenantCode: 'acme', tenantName: '爱思蜜科技', status: 'ENABLED', remark: '主租户',
    contactName: null, contactPhone: null, expiresAt: null,
    packageId: null, packageName: null,
    // REQ-20261122-102954：层级字段（顶层）
    parentId: null, level: 1,
    createdAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    id: '3002', tenantCode: 'beta', tenantName: '贝塔租户', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: null,
    packageId: null, packageName: null,
    // 子租户样例：parent=acme（防环排除用例锚点）
    parentId: '3001', level: 2,
    createdAt: '2026-01-02T00:00:00Z', version: '0',
  },
  {
    id: '3003', tenantCode: 'gamma', tenantName: '伽马租户', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: null,
    packageId: null, packageName: null,
    parentId: null, level: 1,
    createdAt: '2026-01-03T00:00:00Z', version: '0',
  },
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
  statusPuts: Array<{ id: string; body: Record<string, unknown> }>;
  rows: AdminTenantView[];
}

/** 租户 CRUD + 启停 + me 精确 pathname 路由（伴随请求回空信封）。 */
const setupTenantsApi = () => {
  const state: TenantsState = {
    posts: [], statusPuts: [],
    rows: TENANT_ROWS.map((row) => ({ ...row })),
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [] });
    }
    if (p === '/admin/v1/system/tenants/tree' && r.method === 'GET') {
      // 父下拉/层级展示数据源（契约⑩/core tenantTree）：children 嵌套树形
      const tops = state.rows.filter((row) => row.parentId == null);
      const tree = tops.map((top) => ({
        ...top,
        children: state.rows.filter((row) => row.parentId === top.id).map((child) => ({ ...child, children: [] })),
      }));
      return envelope(tree);
    }
    if (p === '/admin/v1/system/tenant-templates' && r.method === 'GET') {
      // 模板目录（AC-21 创建弹窗实时拉取；AC-22 模板必选的选项源）
      return envelope({ records: TEMPLATE_ROWS, total: TEMPLATE_ROWS.length });
    }
    if (p === '/admin/v1/system/tenants/init' && r.method === 'POST') {
      // AC-22：新建提交必走 init 端点（载荷仍携 parentId，本套件 A-3 口径不变）
      const body = parseBody(r.body);
      state.posts.push(body);
      const created = {
        id: '9901', status: 'ENABLED', createdAt: '2026-06-01T00:00:00Z', version: '0',
        packageId: null, packageName: null, level: 1, ...body,
      } as unknown as AdminTenantView;
      state.rows = [created, ...state.rows];
      return envelope(created);
    }
    if (p === '/admin/v1/system/tenants') {
      if (r.method === 'GET') return envelope({ records: state.rows, total: state.rows.length });
      if (r.method === 'POST') {
        const body = parseBody(r.body);
        state.posts.push(body);
        const created = {
          id: '9901', status: 'ENABLED', createdAt: '2026-06-01T00:00:00Z', version: '0',
          packageId: null, packageName: null, level: 1, ...body,
        } as unknown as AdminTenantView;
        state.rows = [created, ...state.rows];
        return envelope(created);
      }
      return envelope(null);
    }
    if (/^\/admin\/v1\/system\/tenants\/[^/]+\/status$/.test(p) && r.method === 'PUT') {
      const body = parseBody(r.body);
      const segments = p.split('/').filter(Boolean);
      const id = segments[segments.length - 2] as string;
      state.statusPuts.push({ id, body });
      state.rows = state.rows.map((item) => (item.id === id ? { ...item, ...body } as AdminTenantView : item));
      return envelope(null);
    }
    if (/^\/admin\/v1\/system\/tenants\/[^/]+$/.test(p) && r.method === 'PUT') {
      const body = parseBody(r.body);
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

/** 读取当前打开浮层内的选项文案（pitfall 7/8/9：限定浮层容器内；Select 选项与 TreeSelect 节点双形态宽容）。 */
const dropdownOptions = async (): Promise<string[]> => {
  await waitFor(() => {
    expect(document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')).toBeTruthy();
  }, { timeout: 5000 });
  const dropdown = document.querySelector<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)') as HTMLElement;
  const texts = (): string[] => Array.from(dropdown.querySelectorAll(
    '.ant-select-item-option-content, .ant-select-tree-title',
  )).map((option) => option.textContent?.trim() ?? '').filter((text) => text.length > 0);
  await waitFor(() => {
    expect(texts().length, '下拉浮层应渲染出选项').toBeGreaterThan(0);
  }, { timeout: 5000 });
  return texts();
};

/** 选项点选：Select 选项或 TreeSelect 节点（精确相等匹配，pitfall 7/8/9）。 */
const pickOption = async (optionText: string): Promise<void> => {
  await waitFor(() => {
    expect(document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')).toBeTruthy();
  }, { timeout: 5000 });
  const dropdown = document.querySelector<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)') as HTMLElement;
  const target = Array.from(dropdown.querySelectorAll(
    '.ant-select-item-option, .ant-select-tree-treenode',
  )).find((option) => {
    const label = option.querySelector('.ant-select-item-option-content, .ant-select-tree-title');
    return label?.textContent?.trim() === optionText;
  });
  if (!target) {
    throw new Error(`option not found: ${optionText}；实际选项=[${
      Array.from(dropdown.querySelectorAll('.ant-select-item-option-content, .ant-select-tree-title'))
        .map((option) => option.textContent?.trim())
        .join(' | ')}]`);
  }
  const clickTarget = target.querySelector('.ant-select-item-option-content, .ant-select-tree-title') ?? target;
  fireEvent.click(clickTarget);
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

/** 行内父/上级选择下拉定位（A-2：label 宽容匹配）。 */
const findSelectByLabel = (dialog: HTMLElement, label: RegExp): HTMLElement =>
  Array.from(dialog.querySelectorAll('.ant-select'))
    .find((select) => label.test(selectDescriptor(select))) as HTMLElement;

/**
 * 触发行内停用（A-5 形态宽容）：优先「停用」按钮，退回行内 Switch。
 * 找不到即显式失败（不静默吞，交 TL 裁决页形态）。
 */
const disableTenantRow = async (anchor: string): Promise<void> => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  const row = cell.closest('tr') as HTMLElement;
  expect(row, `行 ${anchor} 应存在`).toBeTruthy();
  const stopButton = within(row).queryByText(/停\s*用/);
  if (stopButton) {
    fireEvent.click(stopButton);
    return;
  }
  const switchNode = row.querySelector('.ant-switch');
  if (switchNode) {
    fireEvent.click(switchNode);
    return;
  }
  throw new Error(`行 ${anchor} 停用入口未找到（按钮/Switch 双形态均未命中，A-5 假定破）`);
};

/** 确认面（Popconfirm/Modal.confirm）内点确定。 */
const confirmSurface = (): void => {
  const buttons = Array.from(document.querySelectorAll(
    '.ant-popover button, .ant-popconfirm button, .ant-modal-content button, .ant-modal-confirm button',
  ));
  const ok = buttons.find((button) => CONFIRM_BUTTON.test((button.textContent ?? '').trim()));
  expect(ok, '确认面应含确定按钮').toBeTruthy();
  fireEvent.click(ok as HTMLElement);
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

// ————— AC-9：列表层级列 / 父下拉防环 / 级联提示 / 创建携 parentId —————

describe('REQ20261122102954 TenantsPage 子租户层级（AC-9）', () => {
  it('should_render_hierarchy_column_when_list_loaded', async () => {
    await renderPage();
    await screen.findByText('爱思蜜科技', {}, { timeout: 5000 });
    expect(screen.getByText('贝塔租户')).toBeTruthy();
    expect(screen.getByText('伽马租户')).toBeTruthy();

    // 表头：层级列（A-1 宽容：层级/上级/父级任一，契约⑩「缩进树形或 parent 列」）
    const headerText = (document.querySelector('.ant-table-thead') as HTMLElement)?.textContent ?? '';
    expect(headerText, '表头应含层级列（A-1 双形态宽容）').toMatch(/层级|上级|父级/);

    // 子租户行应表达层级：父名直显 或 层级数字标识（A-1）
    const childRow = screen.getByText('贝塔租户').closest('tr') as HTMLElement;
    const childText = childRow?.textContent ?? '';
    const indicatesHierarchy = childText.includes('爱思蜜科技')
      || /层\s*级|上\s*级/.test(childText)
      || /(^|[^0-9])2([^0-9]|$)/.test(childText);
    expect(indicatesHierarchy, '子租户行应表达层级（父名/层级数字/上级标识）').toBe(true);
  });

  it('should_exclude_self_and_descendants_from_parent_options_when_editing', async () => {
    await renderPage();
    await screen.findByText('爱思蜜科技', {}, { timeout: 5000 });

    // 编辑顶层 acme（后代=beta）→ 父下拉排除自身与后代，保留无关系顶层 gamma
    await rowAction('爱思蜜科技', EDIT_BUTTON);
    const dialog = await screen.findByRole('dialog');
    const parentSelect = findSelectByLabel(dialog, PARENT_LABEL);
    expect(parentSelect, '编辑弹窗应含上级/父租户下拉（A-2）').toBeTruthy();
    openSelectDropdown(parentSelect);
    const optionsForTop = await dropdownOptions();
    expect(optionsForTop, '无关系顶层租户应可选').toContain('伽马租户');
    expect(optionsForTop, '父下拉不得含自身（防环）').not.toContain('爱思蜜科技');
    expect(optionsForTop, '父下拉不得含后代（防环）').not.toContain('贝塔租户');
    fireEvent.click(await within(dialog).findByRole('button', { name: CANCEL_BUTTON }));

    // 编辑子租户 beta（无后代）→ 父下拉含顶层租户、排除自身
    await rowAction('贝塔租户', EDIT_BUTTON);
    const dialog2 = await screen.findByRole('dialog');
    const parentSelect2 = findSelectByLabel(dialog2, PARENT_LABEL);
    expect(parentSelect2, '子租户编辑弹窗应含上级/父租户下拉').toBeTruthy();
    openSelectDropdown(parentSelect2);
    const optionsForChild = await dropdownOptions();
    expect(optionsForChild, '顶层父租户应可选').toContain('爱思蜜科技');
    expect(optionsForChild, '无关系顶层租户应可选').toContain('伽马租户');
    expect(optionsForChild, '父下拉不得含自身（防环）').not.toContain('贝塔租户');
  });

  it('should_warn_cascade_and_submit_status_when_disabling_tenant_with_children', async () => {
    const { state } = await renderPage();
    await screen.findByText('爱思蜜科技', {}, { timeout: 5000 });

    // 停用含子租户的父租户 → 级联提示文案（宽出：级联/子租户，pitfall 13/14）
    await disableTenantRow('爱思蜜科技');
    const surfaceText = await waitFor(() => {
      const surface = Array.from(document.querySelectorAll(
        '.ant-popover-content, .ant-popconfirm, .ant-modal-content, .ant-modal-confirm',
      ))
        .map((node) => node.textContent ?? '')
        .find((text) => /级联|子租户/.test(text));
      expect(surface, '停用含子租户的父租户应出现级联提示文案').toBeTruthy();
      return surface as string;
    }, { timeout: 5000 });
    expect(surfaceText).toMatch(/级联|子租户/);

    // 确认 → PUT /tenants/{id}/status 同形（A-4：{status:'DISABLED'}，目标行=父租户 3001）
    confirmSurface();
    await waitFor(() => expect(state.statusPuts).toHaveLength(1));
    expect(state.statusPuts[0]?.id).toBe('3001');
    expect(state.statusPuts[0]?.body.status, '启停端点 body.status=DISABLED（A-4）').toBe('DISABLED');
  });

  it('should_submit_parentId_when_creating_child_tenant', async () => {
    const { state } = await renderPage();

    // 创建子租户：选父「贝塔租户」→ init POST body 携 parentId（A-3 wire 字符串口径；AC-22 补填必选模板/管理员）
    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const dialog = await screen.findByRole('dialog');
    await fillField(dialog, /租\s*户\s*代\s*码/, 'child-zu-hu-it');
    await fillField(dialog, /租\s*户\s*名\s*称/, '子租户甲');
    const templateSelect = findSelectByLabel(dialog, TEMPLATE_LABEL);
    expect(templateSelect, '创建弹窗应含开通模板下拉（AC-22 必选）').toBeTruthy();
    openSelectDropdown(templateSelect);
    await pickOption('标准开通模板');
    await fillField(dialog, /初\s*始\s*管\s*理\s*员\s*账\s*号/, 'childadmin');
    const parentSelect = findSelectByLabel(dialog, PARENT_LABEL);
    expect(parentSelect, '创建弹窗应含上级/父租户下拉（A-2）').toBeTruthy();
    openSelectDropdown(parentSelect);
    await pickOption('贝塔租户');
    await waitFor(() => {
      expect(parentSelect.querySelector('.ant-select-selection-item')?.textContent).toBe('贝塔租户');
    });
    fireEvent.click(await within(dialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.posts).toHaveLength(1));
    expect(state.posts[0]?.tenantCode).toBe('child-zu-hu-it');
    expect(String(state.posts[0]?.parentId), '创建提交应携带所选父租户 id（A-3 字符串口径）').toBe('3002');
    // AC-22 wire 钉死：提交走 init 且模板/初始管理员随载荷提交
    expect(state.posts[0]?.templateCode, 'AC-22：init 载荷携所选模板 code').toBe('std');
    expect(state.posts[0]?.adminUsername, 'AC-22：init 载荷携初始管理员账号').toBe('childadmin');
    await screen.findByText(/已创建/, {}, { timeout: 5000 });

    // 第二次创建：不选父 → parentId 提交 null（顶层默认语义）
    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const dialog2 = await screen.findByRole('dialog');
    await fillField(dialog2, /租\s*户\s*代\s*码/, 'top-zu-hu-it');
    await fillField(dialog2, /租\s*户\s*名\s*称/, '顶层租户乙');
    const templateSelect2 = findSelectByLabel(dialog2, TEMPLATE_LABEL);
    openSelectDropdown(templateSelect2);
    await pickOption('标准开通模板');
    await fillField(dialog2, /初\s*始\s*管\s*理\s*员\s*账\s*号/, 'topadmin');
    fireEvent.click(await within(dialog2).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.posts).toHaveLength(2));
    expect(state.posts[1]?.parentId ?? null, '未选父=顶层（parentId null 提交）').toBeNull();
  });
});
