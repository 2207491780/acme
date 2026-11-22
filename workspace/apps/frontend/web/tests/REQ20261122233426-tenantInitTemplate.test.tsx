// @vitest-environment jsdom
/**
 * REQ-20261122-233426（TENANT-5）TenantsPage 三 Tab 重构面契约用例（AC-6）。
 *
 * 覆盖 AC-6（确认记录⑦⑧）：
 *   - should_switch_three_tabs_when_tab_clicked：CrudTable Tabs 化——租户列表/模板管理/运营概览
 *     三 Tab 切换，各 Tab 内容面切换渲染；
 *   - should_crud_template_with_structured_editor_when_templates_tab_used：模板管理 Tab——
 *     模板清单（含 content 结构化列）、新建模板（rolePresets 结构化编辑器 + 提交载荷）、
 *     编辑模板、删除模板（确认弹层）；
 *   - should_render_overview_cards_chart_and_topn_table_when_overview_tab_opened：运营概览 Tab——
 *     四态统计卡+租户总数+租户分布图+用量 TopN 表（降序锚点）+行内「切换视角」入口；
 *   - should_submit_template_code_with_init_when_template_selected_and_omit_when_absent：
 *     init（新建租户）表单「开通模板」Select——选中模板后提交携 templateCode；
 *     未选=原路径提交（body 无 templateCode）。
 *
 * 装配照既有范本 web/tests/REQ20261122150802-tenantLifecycleQuota.test.tsx（fetch stubGlobal
 * 精确 pathname 路由 + useMeStore 权限播种 + <AntApp> 包裹 + i18n zh-CN）；
 * Select 交互照 pitfalls 卡 7/8 口径（容器内 .ant-select-selector 展开 + 下拉浮层 within 点选）。
 *
 * 【Tabs/Modal 并存口径】antd Tabs 懒挂载后非激活 pane 保留 DOM → 断言一律
 * within(.ant-tabs-tabpane-active) 或 getAllBy* 后经 row/pane 收敛，勿用裸 getByText。
 *
 * 【页面实测事实（self-verify 迭代确认，替代首轮假定）】
 *   F-1 三 Tab=契约⑦钉死词：租户列表/模板管理/运营概览（zh-CN tenants.tabs）；
 *       运营概览 Tab 内 TenantOverviewTab 使用 useNavigate → 渲染须包 <MemoryRouter>。
 *   F-2 模板表单：模板编码/模板名称/备注/默认套餐/管理员账号前缀/管理员昵称 +
 *       「预置角色（rolePresets）」「租户配置项（configEntries）」结构化编辑器；
 *       rolePresets 初始空集，经「添加角色」按钮添加行（行内 roleKey/roleName 文本输入 +
 *       permissionCodes 选择器），空集提交被表单校验拦截（「请输入角色编码」）——确认③
 *       「空角色集保存即拒」的页面同构面。
 *   F-3 模板确认按钮=antd 缺省 OK（Modal 未包 zh locale）；CONFIRM_BUTTON 兼容 确定/OK 双形态。
 *   F-4 init 表单「开通模板」Select：未选模板时初始管理员字段族（初始管理员账号/管理员昵称）
 *       隐藏，选中模板后显现（委派单「选中→初始管理员字段显隐」实测确认），账号输入带
 *       「模板已配置前缀时将自动合成最终账号」占位。
 *   F-5 init 提交路径分流：选中模板 → POST /admin/v1/system/tenants/init 携 templateCode；
 *       未选模板 → POST /admin/v1/system/tenants（原 tenantCreate 路径，body 无 templateCode，
 *       委派单「未选=tenantCreate 原路径」实测确认）。
 *   F-6 运营概览：统计卡=租户总数/启用中/宽限期/已过期/已停用；用量 TopN 表（降序锚点+
 *       userCount 列+行内「切换视角」）；分布图区「租户分布」在 jsdom 走 SVG 渲染面
 *       （canvas=0/svg=1，图形面断言以 canvas/svg 任一就绪承载）。
 *   F-7 模板编辑改行名后删除锚点须用改后名（列表不重置编辑态）。
 *
 * 【i18n 锚点（zh-CN tenants 资源，契约键域 tenants.{tabs,templates,overview}）】
 *   tabs=租户列表/模板管理/运营概览；templates.create=新建模板、createTitle=新建开通模板、
 *   editTitle=编辑模板：{code}、rolePresets 编辑器=预置角色（rolePresets）、
 *   configEntries 编辑器=租户配置项（configEntries）、deleteConfirm=确认删除模板 {code}？…；
 *   overview.cards=启用中/宽限期/已过期/已停用、usageTable=租户用量 Top（按存储用量降序，最多 20）、
 *   switchTo=切换视角；init.template=开通模板。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App as AntApp } from 'antd';
import { i18n, useAuthStore, useMeStore, type AdminTenantView } from '@acme/core';
import { TenantsPage } from '../src/pages/TenantsPage';

vi.setConfig({ testTimeout: 30000 });

// ————— 行为常量（TENANT-4 范本口径，空白宽容匹配） —————
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const EDIT_BUTTON = /编\s*辑/;
const DELETE_BUTTON = /删\s*除/;
const TAB_LIST = /租\s*户\s*列\s*表/;
const TAB_TEMPLATES = /模\s*板\s*管\s*理/;
const TAB_OVERVIEW = /运\s*营\s*概\s*览/;
const CREATE_TEMPLATE_BUTTON = /新\s*建\s*模\s*板/;
const SWITCH_ACTION = /切\s*换\s*视\s*角/;

const PERMS = [
  'system:tenant:list', 'system:tenant:create', 'system:tenant:update',
  'system:tenant-template:list', 'system:tenant-template:create',
  'system:tenant-template:update', 'system:tenant-template:delete',
  'system:tenant:usage-overview',
];

/** 租户列表 Tab 夹具行（与运营视图行码隔离：ten-* vs ov-*）。 */
const TENANT_ROWS: AdminTenantView[] = [
  {
    id: '7301', tenantCode: 'ten-a', tenantName: '苍梧云科技', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: null, graceUntil: null,
    packageId: null, packageName: null, parentId: null, level: 1,
    quotaStorageBytes: 1_073_741_824,
    createdAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    id: '7302', tenantCode: 'ten-b', tenantName: '宽限样本租户', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: null, graceUntil: null,
    packageId: '1', packageName: '基础版', parentId: null, level: 1,
    quotaStorageBytes: 1_073_741_824,
    createdAt: '2026-01-02T00:00:00Z', version: '0',
  },
];

/** 模板 Tab 夹具行（content 形状=确认②原文；审计列 createdAt/updatedAt 无 version——委派点 3）。 */
const TEMPLATE_ROWS: Array<Record<string, unknown>> = [
  {
    id: '801', code: 'tpl-web-a', name: '标准起步模板', remark: '预置基础协作角色',
    content: {
      adminUsernamePrefix: 'startup-',
      adminNickname: '管理员',
      rolePresets: [
        { roleKey: 'ops', roleName: '运维员', permissionCodes: ['system:user:list', 'system:user:create'] },
        { roleKey: 'viewer', roleName: '只读观察', permissionCodes: ['system:user:list'] },
      ],
      configEntries: [{ configKey: 'notify.mail.enabled', value: 'true' }],
    },
    defaultPackageId: '1',
    createdAt: '2026-02-01T00:00:00Z', updatedAt: '2026-02-01T00:00:00Z',
  },
  {
    id: '802', code: 'tpl-web-b', name: '极简模板', remark: null,
    content: {
      rolePresets: [{ roleKey: 'member', roleName: '普通成员', permissionCodes: ['system:user:list'] }],
      configEntries: [],
    },
    defaultPackageId: null,
    createdAt: '2026-02-02T00:00:00Z', updatedAt: '2026-02-03T00:00:00Z',
  },
];

const PACKAGE_ROWS: Array<Record<string, unknown>> = [
  { id: '1', packageName: '基础版', remark: null },
];

/** 运营概览夹具（契约⑥ UsageOverviewView 形状；行码 ov-* 与租户列表隔离）。 */
const OVERVIEW = () => ({
  totalTenants: 6,
  statusCounts: { enabled: 3, grace: 1, expired: 1, disabled: 1 },
  packageDistribution: [
    { packageId: null, packageName: null, tenantCount: 5 },
    { packageId: '1', packageName: '基础版', tenantCount: 1 },
  ],
  tenantUsages: [
    { tenantId: '9001', tenantCode: 'ov-top', tenantName: 'Gamma 样例租户', status: 'ENABLED', storageBytes: 1_073_741_824, quotaBytes: 1_073_741_824, quotaSource: 'PLATFORM_DEFAULT', userCount: 21 },
    { tenantId: '9002', tenantCode: 'ov-mid', tenantName: '苍梧云样本', status: 'ENABLED', storageBytes: 268_435_456, quotaBytes: 1_073_741_824, quotaSource: 'PLATFORM_DEFAULT', userCount: 12 },
    { tenantId: '9003', tenantCode: 'ov-low', tenantName: 'Beta 样本', status: 'ENABLED', storageBytes: 96_468_992, quotaBytes: 5_368_709_120, quotaSource: 'PACKAGE', userCount: 5 },
  ],
});

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

interface TenantsTemplateTabState {
  templatePosts: Array<{ url: string; body: Record<string, unknown> }>;
  templatePuts: Array<{ url: string; body: Record<string, unknown> }>;
  templateDeletes: string[];
  initPosts: Array<{ url: string; body: Record<string, unknown> }>;
  tenantCreates: Array<{ url: string; body: Record<string, unknown> }>;
  overviewGets: number;
  templateRows: Array<Record<string, unknown>>;
}

/** 全端点精确 pathname 路由（租户/模板/套餐/运营视图/init）。 */
const setupTenantsApi = () => {
  const state: TenantsTemplateTabState = {
    templatePosts: [], templatePuts: [], templateDeletes: [], initPosts: [], tenantCreates: [],
    overviewGets: 0, templateRows: TEMPLATE_ROWS.map((row) => ({ ...row, content: structuredClone((row as { content: unknown }).content) })),
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [] });
    }
    if (p === '/admin/v1/system/tenants/usage-overview') {
      state.overviewGets += 1;
      return envelope(OVERVIEW());
    }
    if (p === '/admin/v1/system/tenant-templates' && r.method === 'GET') {
      return envelope({ records: state.templateRows, total: state.templateRows.length });
    }
    if (p === '/admin/v1/system/tenant-templates' && r.method === 'POST') {
      const body = parseBody(r.body);
      state.templatePosts.push({ url: p, body });
      const created = { id: '9801', createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z', ...body };
      state.templateRows = [...state.templateRows, created];
      return envelope(created);
    }
    const templatePath = /^\/admin\/v1\/system\/tenant-templates\/([^/]+)$/.exec(p);
    if (templatePath && r.method === 'PUT') {
      const body = parseBody(r.body);
      state.templatePuts.push({ url: p, body });
      const id = templatePath[1] as string;
      const target = state.templateRows.find((row) => row.id === id);
      if (!target) return envelope(null);
      const updated = { ...target, ...body };
      state.templateRows = state.templateRows.map((row) => (row.id === id ? updated : row));
      return envelope(updated);
    }
    if (templatePath && r.method === 'DELETE') {
      state.templateDeletes.push(templatePath[1] as string);
      state.templateRows = state.templateRows.filter((row) => row.id !== templatePath[1]);
      return new Response(null, { status: 200 });
    }
    if (p === '/admin/v1/system/tenant-packages' && r.method === 'GET') {
      return envelope({ records: PACKAGE_ROWS, total: PACKAGE_ROWS.length });
    }
    if (p === '/admin/v1/system/tenants/init' && r.method === 'POST') {
      const body = parseBody(r.body);
      state.initPosts.push({ url: p, body });
      return envelope({ id: '9901', tenantCode: body.tenantCode, tenantName: body.tenantName,
        adminUsername: body.adminUsername, adminNickname: body.adminNickname ?? null });
    }
    if (p === '/admin/v1/system/tenants/tree' && r.method === 'GET') {
      const tree = TENANT_ROWS.filter((row) => row.parentId == null).map((top) => ({ ...top, children: [] }));
      return envelope(tree);
    }
    if (p === '/admin/v1/system/tenants') {
      if (r.method === 'GET') return envelope({ records: TENANT_ROWS, total: TENANT_ROWS.length });
      if (r.method === 'POST') {
        const body = parseBody(r.body);
        state.tenantCreates.push({ url: p, body });
        return envelope({ id: '9902', createdAt: '2026-06-01T00:00:00Z', version: '0',
          packageId: null, packageName: null, parentId: null, level: 1, ...body });
      }
      return envelope(null);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const renderPage = async (): Promise<{ state: TenantsTemplateTabState }> => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [],
  })));
  await useMeStore.getState().load();
  const api = setupTenantsApi();
  render(<MemoryRouter><AntApp><TenantsPage /></AntApp></MemoryRouter>);
  return api;
};

// ————— Tabs 口径支撑 —————

/** 激活 TabPane（antd 懒挂载后非激活 pane 保留 DOM——断言一律收敛到激活面）。 */
const activePane = (): HTMLElement => {
  const pane = document.querySelector<HTMLElement>('.ant-tabs-tabpane-active');
  expect(pane, '应存在激活中的 TabPane').toBeTruthy();
  return pane as HTMLElement;
};

const clickTab = async (name: RegExp): Promise<void> => {
  const tab = await screen.findByRole('tab', { name }, { timeout: 5000 });
  fireEvent.click(tab);
  await waitFor(() => {
    expect((tab as HTMLElement).getAttribute('aria-selected')).toBe('true');
  }, { timeout: 5000 });
};

/** 激活面行锚定：按文本找行（pitfall 9：a11y 镜像差——直接经 td→tr 定位）。 */
const rowByAnchor = async (anchor: string): Promise<HTMLElement> => {
  const cell = await within(activePane()).findByText(anchor, {}, { timeout: 5000 });
  const row = cell.closest('tr') as HTMLElement;
  expect(row, `行 ${anchor} 应存在`).toBeTruthy();
  return row;
};

/** 表单字段定位（label 就近 .ant-form-item，TENANT-4 fillDatePicker 同款口径）。 */
const fieldItemIn = (container: HTMLElement, label: RegExp): HTMLElement => {
  const labelNode = Array.from(container.querySelectorAll('label'))
    .find((node) => label.test(node.textContent ?? ''));
  expect(labelNode, `表单应含 ${label} 字段 label`).toBeTruthy();
  return (labelNode as HTMLElement).closest('.ant-form-item') as HTMLElement;
};

const fillInput = (container: HTMLElement, label: RegExp, value: string): void => {
  const item = fieldItemIn(container, label);
  const input = item.querySelector<HTMLInputElement>('input.ant-input, input[type="text"], input');
  expect(input, `表单 ${label} 字段应含可输入控件`).toBeTruthy();
  fireEvent.change(input as HTMLInputElement, { target: { value } });
};

/** Select 展开+点选（pitfalls 卡 7/8：mousedown/click 发到容器内 .ant-select-selector，
 *  选项在下拉浮层 .ant-select-dropdown 内点选，勿对裸 combobox 直接操作）。 */
const pickSelectOption = async (container: HTMLElement, label: RegExp, option: RegExp): Promise<void> => {
  const item = fieldItemIn(container, label);
  const selector = item.querySelector('.ant-select-selector') as HTMLElement;
  expect(selector, `表单 ${label} 字段应含 Select 容器`).toBeTruthy();
  fireEvent.mouseDown(selector);
  fireEvent.click(selector);
  const dropdown = await waitFor(() => {
    const overlay = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-dropdown'))
      .find((node) => !node.classList.contains('ant-select-dropdown-hidden') && node.style.display !== 'none');
    expect(overlay, `${label} 下拉浮层应展开`).toBeTruthy();
    return overlay as HTMLElement;
  }, { timeout: 5000 });
  const optionNode = await within(dropdown).findByText(option, {}, { timeout: 5000 });
  const optionItem = (optionNode.closest('.ant-select-item-option')
    ?? optionNode.closest('.ant-select-item')) as HTMLElement;
  expect(optionItem, `下拉浮层内应含可点选选项 ${option}`).toBeTruthy();
  fireEvent.click(optionItem);
};

/** 可见 Modal 表面（过滤已关闭未卸载残留，TENANT-4 同款）。 */
const visibleModals = (keyword: RegExp): HTMLElement[] =>
  Array.from(document.querySelectorAll<HTMLElement>('.ant-modal-content, .ant-drawer-content'))
    .filter((node) => keyword.test(node.textContent ?? ''))
    .filter((node) => {
      const wrap = node.closest('.ant-modal-wrap') as HTMLElement | null;
      return !wrap || wrap.style.display !== 'none';
    });

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

// ————— AC-6：三 Tab 切换 / 模板 CRUD / 运营概览 / init 模板 Select —————

describe('REQ20261122233426 TenantsPage 三 Tab 重构面（AC-6）', () => {
  it('should_switch_three_tabs_when_tab_clicked', async () => {
    await renderPage();
    // 默认 Tab=租户列表：租户行渲染（activePane 收敛，防隐藏 pane 误命中）
    await within(activePane()).findByText('苍梧云科技', {}, { timeout: 5000 });

    // 模板管理 Tab：模板清单渲染
    await clickTab(TAB_TEMPLATES);
    await within(activePane()).findByText('标准起步模板', {}, { timeout: 5000 });
    expect(within(activePane()).getByText('tpl-web-a')).toBeTruthy();
    expect(within(activePane()).getByText('极简模板')).toBeTruthy();

    // 运营概览 Tab：统计卡/用量表渲染
    await clickTab(TAB_OVERVIEW);
    await within(activePane()).findByText(/租\s*户\s*总\s*数/, {}, { timeout: 5000 });

    // 回切租户列表：租户行仍渲染
    await clickTab(TAB_LIST);
    await within(activePane()).findByText('宽限样本租户', {}, { timeout: 5000 });
  });

  it('should_crud_template_with_structured_editor_when_templates_tab_used', async () => {
    const { state } = await renderPage();
    await clickTab(TAB_TEMPLATES);
    await within(activePane()).findByText('标准起步模板', {}, { timeout: 5000 });

    // ① 新建模板：结构化编辑器（rolePresets/configEntries）在表单内 → 提交携 content 结构
    fireEvent.click(await within(activePane()).findByText(CREATE_TEMPLATE_BUTTON));
    const createDialog = await awaitSurface(/新\s*建\s*开\s*通\s*模\s*板/);
    expect(within(createDialog).getAllByText(/预\s*置\s*角\s*色/).length,
      '新建表单应含 rolePresets 结构化编辑器（编辑器标题/列族多命中宽容）').toBeGreaterThan(0);
    expect(within(createDialog).getAllByText(/配\s*置\s*项/).length,
      '新建表单应含 configEntries 结构化编辑器').toBeGreaterThan(0);
    fillInput(createDialog, /模\s*板\s*编\s*码/, 'tpl-web-new');
    fillInput(createDialog, /模\s*板\s*名\s*称/, 'Web 集成模板');
    // rolePresets 结构化编辑：预置角色初始空集 → 添加角色 → 编辑器项内填 roleKey/roleName
    fireEvent.click(await within(createDialog).findByText(/添\s*加\s*角\s*色/));
    await waitFor(() => {
      expect(within(createDialog).getAllByText(/预\s*置\s*角\s*色/).length).toBeGreaterThan(0);
    });
    const rpLabel = Array.from(createDialog.querySelectorAll('label'))
      .find((node) => /预\s*置\s*角\s*色/.test(node.textContent ?? ''));
    expect(rpLabel, 'rolePresets 编辑器应以 Form.Item label 承载').toBeTruthy();
    const rpSection = (rpLabel as HTMLElement).closest('.ant-form-item') as HTMLElement;
    const rpInputs = Array.from(rpSection.querySelectorAll<HTMLInputElement>('input.ant-input'))
      .filter((input) => !input.disabled);
    expect(rpInputs.length, '编辑器项应含 roleKey/roleName 可填输入').toBeGreaterThanOrEqual(2);
    fireEvent.change(rpInputs[0], { target: { value: 'ops' } });
    fireEvent.change(rpInputs[1], { target: { value: '运维员' } });
    fireEvent.click(await within(createDialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.templatePosts.length).toBeGreaterThan(0), { timeout: 8000 });
    const createPost = state.templatePosts[0] as { url: string; body: Record<string, unknown> };
    expect(createPost.url).toBe('/admin/v1/system/tenant-templates');
    expect(createPost.body.code).toBe('tpl-web-new');
    expect(createPost.body.name).toBe('Web 集成模板');
    expect(createPost.body.content, '模板保存载荷应携带 content 结构（确认②）').toBeTruthy();
    const postedPresets = (createPost.body.content as Record<string, unknown>).rolePresets as Array<Record<string, unknown>>;
    expect(Array.isArray(postedPresets) && postedPresets.length >= 1,
      'content.rolePresets 结构化载荷（经编辑器添加行）').toBe(true);
    expect(postedPresets[0]?.roleKey).toBe('ops');
    expect(postedPresets[0]?.roleName).toBe('运维员');

    // ② 编辑模板：editTitle 按 code 锚定 → 名称修改 → PUT /{id}
    const row = await rowByAnchor('标准起步模板');
    fireEvent.click(within(row).getByText(EDIT_BUTTON));
    const editDialog = await awaitSurface(/编\s*辑\s*模\s*板\s*：\s*tpl-web-a/);
    fillInput(editDialog, /模\s*板\s*名\s*称/, '标准起步模板-改');
    fireEvent.click(await within(editDialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.templatePuts.length).toBeGreaterThan(0), { timeout: 5000 });
    const editPut = state.templatePuts[0] as { url: string; body: Record<string, unknown> };
    expect(editPut.url, 'PUT 应按模板 id 寻址').toBe('/admin/v1/system/tenant-templates/801');
    expect(editPut.body.name).toBe('标准起步模板-改');

    // ③ 删除模板：确认弹层文案（含快照语义提示）→ 确定 → DELETE /{id}
    // （编辑已改行名 → 删除行按改后名锚定）
    const rowForDelete = await rowByAnchor('标准起步模板-改');
    fireEvent.click(within(rowForDelete).getByText(DELETE_BUTTON));
    const confirmText = await screen.findByText(/确\s*认\s*删\s*除\s*模\s*板/, {}, { timeout: 5000 });
    expect(confirmText.textContent, '删除确认应含模板 code').toContain('tpl-web-a');
    const popover = confirmText.closest('.ant-popover') ?? confirmText.closest('.ant-popconfirm') ?? document.body;
    fireEvent.click(await within(popover as HTMLElement).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.templateDeletes.length).toBeGreaterThan(0), { timeout: 5000 });
    expect(state.templateDeletes[0]).toBe('801');
  });

  it('should_render_overview_cards_chart_and_topn_table_when_overview_tab_opened', async () => {
    const { state } = await renderPage();
    await clickTab(TAB_OVERVIEW);
    const pane = activePane();
    await within(pane).findByText(/租\s*户\s*总\s*数/, {}, { timeout: 5000 });

    // ① 四态统计卡 + 租户总数（契约⑥ statusCounts 四键 → 统计卡族）
    for (const card of ['启用中', '宽限期', '已过期', '已停用']) {
      expect(within(pane).getAllByText(new RegExp(card.split('').join('\\s*'))).length,
        `运营概览应含「${card}」统计卡`).toBeGreaterThan(0);
    }
    await waitFor(() => expect(state.overviewGets, '运营概览应拉取 usage-overview').toBeGreaterThan(0));

    // ② 用量 TopN 表：标题/表头/行锚定/降序锚点/行内切换视角入口（契约⑥⑦）
    expect(within(pane).getByText(/租\s*户\s*用\s*量\s*Top/)).toBeTruthy();
    const topRow = await rowByAnchor('ov-top');
    const midRow = await rowByAnchor('ov-mid');
    const lowRow = await rowByAnchor('ov-low');
    expect(within(topRow).getByText(/(^|[^\d])21([^\d]|$)/), '用量行应呈现 userCount（21）').toBeTruthy();
    expect(within(topRow).getByText(SWITCH_ACTION), '用量行应含「切换视角」入口（确认⑦ tenant-switch 复用）').toBeTruthy();
    const table = topRow.closest('table') as HTMLElement;
    expect(table, '用量行应同属一张表格（TopN 表）').toBeTruthy();
    const positions = [topRow, midRow, lowRow].map((row) =>
      Array.from(table.querySelectorAll('tbody tr')).indexOf(row as HTMLTableRowElement));
    expect(positions[0], '按 storageBytes 降序：ov-top 在 ov-mid 之前').toBeLessThan(positions[1]);
    expect(positions[1], '按 storageBytes 降序：ov-mid 在 ov-low 之前').toBeLessThan(positions[2]);

    // ③ 分布图：租户分布区（echarts 懒加载在 jsdom 走 SVG 渲染面——canvas/svg 任一即视为图形面就绪）
    const chartTitle = within(pane).getByText(/租\s*户\s*分\s*布/);
    const chartCard = chartTitle.closest('.ant-card') ?? pane;
    await waitFor(() => {
      expect(chartCard.querySelector('canvas, svg'), '运营概览分布图区应渲染图形面（jsdom SVG/canvas 近似锚点）').toBeTruthy();
    }, { timeout: 10000 });
  });

  it('should_submit_template_code_with_init_when_template_selected_and_omit_when_absent', async () => {
    const { state } = await renderPage();
    await within(activePane()).findByText('苍梧云科技', {}, { timeout: 5000 });

    // ① 选中模板：init 提交携 templateCode
    fireEvent.click(await within(activePane()).findByText(/新\s*建\s*租\s*户/));
    const dialog = await awaitSurface(/新\s*建\s*租\s*户|开\s*通\s*租\s*户/);
    fillInput(dialog, /租\s*户\s*代\s*码/, 'init-tpl');
    fillInput(dialog, /租\s*户\s*名\s*称/, '模板开通夹具租户');
    await pickSelectOption(dialog, /开\s*通\s*模\s*板/, /标\s*准\s*起\s*步\s*模\s*板/);
    // 选中模板后初始管理员字段显现（委派单「选中→初始管理员字段显隐」锚点：未选模板时该字段族隐藏）
    await waitFor(() => {
      const shown = Array.from(dialog.querySelectorAll('label'))
        .some((node) => /初\s*始\s*管\s*理\s*员\s*账\s*号/.test(node.textContent ?? ''));
      expect(shown, '选中模板后应显现初始管理员账号字段').toBe(true);
    }, { timeout: 5000 });
    const adminItem = fieldItemIn(dialog, /初\s*始\s*管\s*理\s*员\s*账\s*号/);
    const adminInput = adminItem.querySelector<HTMLInputElement>('input');
    expect(within(adminItem).getByPlaceholderText(/模\s*板\s*已\s*配\s*置\s*前\s*缀/),
      '初始管理员账号输入应带模板前缀合成提示').toBeTruthy();
    if (!adminInput?.value) {
      fireEvent.change(adminInput as HTMLInputElement, { target: { value: 'tplboss' } });
    }
    fireEvent.click(await within(dialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.initPosts.length).toBeGreaterThan(0), { timeout: 5000 });
    const withTemplate = state.initPosts[0] as { url: string; body: Record<string, unknown> };
    expect(withTemplate.url, '选中模板=init 提交路径').toBe('/admin/v1/system/tenants/init');
    expect(withTemplate.body.templateCode, '提交应携带 templateCode（确认③）').toBe('tpl-web-a');
    expect(withTemplate.body.tenantCode).toBe('init-tpl');

    // ② 未选模板：走原 tenantCreate 路径（POST /tenants，无 templateCode/管理员字段——
    // 初始管理员字段族保持隐藏，显隐对照；确认③「未传=现行为」= 原 create 面）
    // 先等场景①弹窗完全关闭（antd 关闭动画期 display 未切换，防旧 Modal 误命中）
    await waitFor(() => {
      expect(visibleModals(/新\s*建\s*租\s*户|开\s*通\s*租\s*户/).length).toBe(0);
    }, { timeout: 8000 });
    fireEvent.click(await within(activePane()).findByText(/新\s*建\s*租\s*户/));
    const dialog2 = await awaitSurface(/新\s*建\s*租\s*户|开\s*通\s*租\s*户/);
    fillInput(dialog2, /租\s*户\s*代\s*码/, 'init-plain');
    fillInput(dialog2, /租\s*户\s*名\s*称/, '无模板夹具租户');
    const adminLabelShown = Array.from(dialog2.querySelectorAll('label'))
      .some((node) => /初\s*始\s*管\s*理\s*员\s*账\s*号/.test(node.textContent ?? ''));
    expect(adminLabelShown, '未选模板时初始管理员账号字段保持隐藏（显隐对照面）').toBe(false);
    fireEvent.click(await within(dialog2).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.tenantCreates.length).toBeGreaterThanOrEqual(1), { timeout: 8000 });
    expect(state.initPosts.length, '未选模板不走 init 端点（原 create 路径）').toBe(1);
    const withoutTemplate = state.tenantCreates[0] as { url: string; body: Record<string, unknown> };
    expect(withoutTemplate.url, '未选模板=原 tenantCreate 提交路径（委派单钉死口径）')
      .toBe('/admin/v1/system/tenants');
    expect(withoutTemplate.body.tenantCode).toBe('init-plain');
    const submittedTemplateCode = withoutTemplate.body.templateCode;
    expect(submittedTemplateCode === undefined || submittedTemplateCode === null || submittedTemplateCode === '',
      '原 create 路径不携带 templateCode（确认③兼容）').toBe(true);
  });
});
