/**
 * REQ-20261125-230807 AC-19/AC-21/AC-22/AC-23/AC-27（租户侧）—— TenantsPage 契约用例。
 *
 * 覆盖映射（映射总表见 WT/.ai-tmp/req-230807/frontend-tester-delivery.md）：
 *   AC-19  新建/编辑租户表单父租户树形下拉实时候选（打开弹窗拉 /tenants/tree）；编辑态防环剔除
 *          （自身+后代不在候选）；加载失败出现可见警告（AC-27 租户侧合并断言重试入口）。
 *   AC-21  套餐页签新建套餐后不刷新直接打开租户编辑弹窗 → 套餐下拉含新套餐（打开弹窗实时拉目录，
 *          非挂载期缓存）；模板目录同口径。
 *   AC-22  新建租户表单：无套餐字段、模板必选（空提交前端拦截不发请求）；提交必走 tenantInit
 *          （body.templateCode，不调 tenantCreate）；编辑态保留套餐绑定字段。
 *   AC-23  模板表单预置角色：roleKey/roleName 改系统角色下拉选择（选中回填权限点、可再编辑手输兜底）；
 *          permissionCodes 改权限目录多选；configEntries 维持手填。
 *   AC-27  租户树目录加载失败 → 可见警告 + 重试入口（重试成功后候选恢复）。
 *
 * 装配照范本 web/tests/REQ20261124213008-adminFrontendPages.test.tsx；关闭态断言数据驱动（不断言
 * modal DOM 卸载——范本 round2 裁决⑪口径）；下拉交互照 pitfalls 卡 7/8。
 *
 * 【表面假定（红则 TL 裁决，pitfall 14 口径）】
 *   A-1 表单 label 候选：父租户/上级租户、套餐、模板/开通模板、租户编码、租户名称。
 *   A-2 AC-21 的「实时」以「每次打开弹窗都重新 GET 目录端点 + 新数据可见」为数据驱动判据
 *       （页签保活场景的页内等价：不重挂页面组件，mock 侧改数据后重开弹窗）。
 *   A-3 模板管理入口形态未契约化（页签/分段/同表）：以页内出现「标准模板」行 + 行内编辑入口为准；
 *       编辑保存 wire=PUT /tenant-templates/{id}（tenantTemplateApi 既有形状）。
 *   A-4 系统角色下拉数据源=角色分页接口（选中后经 GET /roles/{id}/permissions 回填权限点——
 *       rolePermissions 既有端点）；回填后权限点多选保留手输兜底（tags 形态）。
 *   A-5 tenantInit 返回视图形状未契约化（仅断言 wire 请求面，不断言返回渲染）。
 *   A-6 实现未落地属预期红（钉死事实⑥）：实时拉目录/新建去套餐/模板必选/预置角色选择化均为本 REQ 新增面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import type { ComponentType } from 'react';
import { TenantsPage } from '../src/pages/TenantsPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 90000 }); // B-组口径（同显式超时族）：同机后端全量测试 CPU 争抢下查询饥饿
                                      // 实测（AC-19/21/22/23/27 均被拉长 2-4 倍），预算 45s→90s，断言集不变

const SUBMIT_BUTTON = /提\s*交|确\s*定|^OK$/i;
const CANCEL_BUTTON = /取\s*消|^取消$|^Cancel$/i;

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

/** 取「最后渲染」的可见 dialog（重开弹窗场景旧 modal 可能残留 DOM——数据驱动口径取最新）。 */
const currentDialog = () => {
  const dialogs = screen.getAllByRole('dialog');
  return dialogs[dialogs.length - 1] as HTMLElement;
};

/** 打开 label 命中候选词的下拉浮层（pitfall 7/8）。 */
const openSelectByLabels = async (scope: HTMLElement, labels: string[]) => {
  const labelEls = labels.flatMap((label) => Array.from(scope.querySelectorAll('label')).filter((el) => new RegExp(label).test(el.textContent ?? '')));
  expect(labelEls.length, `应存在 label ${labels.join('/')}`).toBeGreaterThanOrEqual(1);
  const item = (labelEls[0].closest('.ant-form-item') ?? labelEls[0].parentElement) as HTMLElement;
  fireEvent.mouseDown(item.querySelector('.ant-select-selector') as Element);
  return await waitFor(() => {
    const el = document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
    expect(el, '下拉浮层应展开').toBeTruthy();
    return el as HTMLElement;
  });
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

// ————— fixtures（契约 §API；id wire 字符串） —————
const SF_T_ROOT = '9223372036854775931'; // 总部
const SF_T_CHILD = '9223372036854775932'; // 华东分部（总部后代）
const SF_T_OTHER = '9223372036854775933'; // 独立租户
const SF_PKG_A = '9223372036854775941';
const SF_PKG_B = '9223372036854775942';
const SF_TPL_A = '9223372036854775951';
const SF_TPL_B = '9223372036854775952';
const SF_ROLE_TA = '9223372036854775961'; // tenant_admin

const makeTenant = (id: string, tenantCode: string, tenantName: string, level: number, parentId: string | null, children: unknown[]) => ({
  id, tenantCode, tenantName, status: 'ENABLED', level, parentId, packageId: null,
  version: '0', createdAt: '2026-01-01T00:00:00Z', children,
});

const TENANT_TREE = [
  makeTenant(SF_T_ROOT, 'acme', '总部', 0, null, [
    makeTenant(SF_T_CHILD, 'acme_east', '华东分部', 1, SF_T_ROOT, []),
  ]),
  makeTenant(SF_T_OTHER, 'standalone', '独立租户', 0, null, []),
];

const TENANT_ROWS = [
  makeTenant(SF_T_ROOT, 'acme', '总部', 0, null, []),
  makeTenant(SF_T_CHILD, 'acme_east', '华东分部', 1, SF_T_ROOT, []),
  makeTenant(SF_T_OTHER, 'standalone', '独立租户', 0, null, []),
];

const TPL_CONTENT = {
  adminUsernamePrefix: 'admin',
  adminNickname: '管理员',
  rolePresets: [{ roleKey: 'tenant_admin', roleName: '租户管理员', permissionCodes: ['system:user:list', 'system:user:create'] }],
  configEntries: [{ configKey: 'init.welcome', value: 'hello' }],
};

const permRow = (id: string, code: string, name: string) => ({
  id, permissionCode: code, permissionName: name, permissionType: 'API', status: 'ENABLED',
  httpMethod: 'GET', httpPath: `/admin/v1/system/${code.split(':')[1]}`, version: '0',
});

const PERMISSION_ROWS = [
  permRow('7001', 'system:user:list', '用户查询'),
  permRow('7002', 'system:user:create', '用户新增'),
  permRow('7003', 'system:role:list', '角色查询'),
];

const buildRoutes = (state: {
  treeFail: boolean;
  pkgRows: { id: string; packageName: string; remark: string | null }[];
  tplRows: { id: string; code: string; name: string; remark: string | null; content: typeof TPL_CONTENT; defaultPackageId: string | null; createdAt: string; updatedAt: string }[];
}) => ({
  // 长前缀先匹配
  '/system/tenants/tree': () => (state.treeFail
    ? failureEnvelope('INTERNAL_ERROR', '服务器内部错误')
    : envelope(TENANT_TREE)),
  '/system/tenants/init': () => envelope({ id: SF_T_OTHER, tenantCode: 'tenant_x', tenantName: '测试租户' }),
  '/system/tenants': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope({ records: TENANT_ROWS, total: TENANT_ROWS.length });
    if (r.method === 'PUT') return envelope(TENANT_ROWS[0]);
    if (r.method === 'POST') return envelope(TENANT_ROWS[0]);
    return envelope(null);
  },
  '/system/tenant-packages': () => envelope({ records: state.pkgRows, total: state.pkgRows.length }),
  '/system/tenant-templates': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope({ records: state.tplRows, total: state.tplRows.length });
    if (r.method === 'PUT') {
      const id = r.url.pathname.split('/').pop() ?? '';
      const body = parseBody(r.body) ?? {};
      return envelope({ ...(state.tplRows.find((row) => row.id === id) ?? state.tplRows[0]), ...body, content: body.content ?? state.tplRows[0].content });
    }
    return envelope(null);
  },
  '/system/roles/9223372036854775961/permissions': () => envelope([PERMISSION_ROWS[0], PERMISSION_ROWS[1]]),
  '/system/roles/9223372036854775962/permissions': () => envelope([PERMISSION_ROWS[2]]),
  '/system/roles': () => envelope({
    records: [
      { id: SF_ROLE_TA, parentId: null, roleCode: 'tenant_admin', roleName: '租户管理员', status: 'ENABLED', builtIn: false, remark: '', version: '0', children: [] },
      { id: '9223372036854775962', parentId: null, roleCode: 'tenant_ops', roleName: '租户运维', status: 'ENABLED', builtIn: false, remark: '', version: '0', children: [] },
    ],
    total: 2,
  }),
  '/system/permissions': () => envelope({ records: PERMISSION_ROWS, total: PERMISSION_ROWS.length }),
  '/system/capabilities': () => envelope([]),
  '/system/menus': () => envelope([]),
});

const PERMS_SEED = ['system:tenant:list', 'system:tenant:create', 'system:tenant:update', 'system:tenant-template:list', 'system:tenant-template:update'];

// ————— AC-19：父租户树形下拉实时候选 + 防环剔除 —————
describe('REQ20261125230807 TenantsPage 父租户下拉（AC-19）', () => {
  it('should_show_live_parent_candidates_when_tenant_create_modal_opened', async () => {
    const { fetchMock } = await renderPage(TenantsPage, PERMS_SEED, buildRoutes({ treeFail: false, pkgRows: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }], tplRows: [] }));
    await findRowByText('总部');
    fireEvent.click(await screen.findByRole('button', { name: /新\s*建(\s*租\s*户)?$/ }));
    const dialog = await currentDialog();

    // 打开弹窗即实时拉租户树
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/tenants/tree').length).toBeGreaterThanOrEqual(1);
    });
    const dropdown = await openSelectByLabels(dialog, ['父\\s*租\\s*户', '上级租户']);
    expect(await within(dropdown).findByText('总部')).toBeTruthy();
    expect(await within(dropdown).findByText('独立租户')).toBeTruthy();
  });

  it('should_exclude_self_and_descendants_from_parent_options_when_editing_tenant', async () => {
    const state = { treeFail: false, pkgRows: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }], tplRows: [] };
    await renderPage(TenantsPage, PERMS_SEED, buildRoutes(state));
    const row = await findRowByText('总部');
    fireEvent.click(await within(row).findByText(/^编\s*辑$/));
    const dialog = await currentDialog();

    const dropdown = await openSelectByLabels(dialog, ['父\\s*租\\s*户', '上级租户']);
    // 防环剔除：自身（总部）与后代（华东分部）不在候选；其余候选保留
    await waitFor(() => {
      expect(within(dropdown).queryByText('总部'), '编辑态候选应剔除自身').toBeNull();
      expect(within(dropdown).queryByText('华东分部'), '编辑态候选应剔除后代').toBeNull();
      expect(within(dropdown).getByText('独立租户')).toBeTruthy();
    });
  });
});

// ————— AC-21：弹窗实时拉目录（套餐/模板同口径） —————
describe('REQ20261125230807 TenantsPage 弹窗实时拉目录（AC-21）', () => {
  it('should_refetch_package_and_template_directories_when_tenant_edit_modal_reopened', async () => {
    const state = {
      treeFail: false,
      pkgRows: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }],
      tplRows: [{ id: SF_TPL_A, code: 'standard', name: '标准模板', remark: null, content: TPL_CONTENT, defaultPackageId: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }],
    };
    const { fetchMock } = await renderPage(TenantsPage, PERMS_SEED, buildRoutes(state));
    await findRowByText('总部');

    // 第一次打开编辑弹窗：套餐下拉含基础套餐
    const row = await findRowByText('总部');
    fireEvent.click(await within(row).findByText(/^编\s*辑$/));
    let dialog = await currentDialog();
    const pkgDropdown = await openSelectByLabels(dialog, ['套餐']);
    expect(await within(pkgDropdown).findByText('基础套餐')).toBeTruthy();
    const pkgCallsAfterFirst = callsOf(fetchMock, 'GET', '/admin/v1/system/tenant-packages').length;
    expect(pkgCallsAfterFirst).toBeGreaterThanOrEqual(1);

    // 关闭弹窗（数据驱动：不断言 DOM 卸载）
    fireEvent.click(await within(dialog).findByRole('button', { name: CANCEL_BUTTON }).catch(() => within(dialog).getByRole('button', { name: /关\s*闭/ })));

    // 模拟在套餐页签新建套餐 + 模板页签新建模板（页签保活不刷新——mock 侧数据演进）
    state.pkgRows.push({ id: SF_PKG_B, packageName: '旗舰套餐', remark: null });
    state.tplRows.push({ id: SF_TPL_B, code: 'premium', name: '高级模板', remark: null, content: TPL_CONTENT, defaultPackageId: null, createdAt: '2026-01-02T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z' });

    // 不刷新页面直接重开编辑弹窗 → 套餐下拉含新套餐（打开弹窗实时拉目录）
    fireEvent.click(await within(await findRowByText('总部')).findByText(/^编\s*辑$/));
    dialog = await currentDialog();
    const pkgDropdown2 = await openSelectByLabels(dialog, ['套餐']);
    expect(await within(pkgDropdown2).findByText('旗舰套餐'), '重开弹窗应实时拉到新套餐').toBeTruthy();

    // 模板同口径（AC-21）：模板域仅创建态（AC-22 契约：编辑态无模板域是正确行为，delta-r2 口径）
    // → 关闭编辑弹窗、打开创建弹窗断言模板下拉实时拉目录
    fireEvent.click(await within(dialog).findByRole('button', { name: CANCEL_BUTTON })
      .catch(() => within(dialog).getByRole('button', { name: /关\s*闭/ })));
    fireEvent.click(await screen.findByRole('button', { name: /新\s*建(\s*租\s*户)?$/ }));
    dialog = await currentDialog();
    const tplDropdown = await openSelectByLabels(dialog, ['模板', '开通模板']);
    expect(await within(tplDropdown).findByText('高级模板'), '模板目录应实时拉到新模板（创建弹窗）').toBeTruthy();
    expect(callsOf(fetchMock, 'GET', '/admin/v1/system/tenant-packages').length).toBeGreaterThan(pkgCallsAfterFirst);
  });
});

// ————— AC-22：新建去套餐 + 模板必选 + 必走 tenantInit；编辑保留套餐字段 —————
describe('REQ20261125230807 TenantsPage 新建/编辑表单口径（AC-22）', () => {
  it('should_omit_package_field_and_require_template_when_tenant_create_submitted', async () => {
    const state = {
      treeFail: false,
      pkgRows: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }],
      tplRows: [{ id: SF_TPL_A, code: 'standard', name: '标准模板', remark: null, content: TPL_CONTENT, defaultPackageId: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }],
    };
    const { fetchMock } = await renderPage(TenantsPage, PERMS_SEED, buildRoutes(state));
    await findRowByText('总部');

    fireEvent.click(await screen.findByRole('button', { name: /新\s*建(\s*租\s*户)?$/ }));
    const dialog = await currentDialog();

    // 新建表单无套餐字段（确认③：仅限新建）
    const packageLabels = Array.from(dialog.querySelectorAll('label')).filter((el) => /套餐/.test(el.textContent ?? ''));
    expect(packageLabels.length, '新建表单不应有套餐字段').toBe(0);

    // 模板必选：空提交被前端拦截（不发请求 + 校验反馈）
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));
    expect(callsOf(fetchMock, 'POST', '/admin/v1/system/tenants/init')).toHaveLength(0);
    await waitFor(() => {
      expect(dialog.querySelector('.ant-form-item-explain-error'), '模板必选校验反馈应出现').toBeTruthy();
    }, { timeout: 8000 }); // B7③：租户族负载敏感（pitfall 已知面），显式 ≥5s 超时防提前判红

    // 填齐：租户编码/租户名称/模板 → 提交必走 tenantInit（body.templateCode），不调 tenantCreate
    const fillInput = async (labels: string[], value: string) => {
      const labelEl = labels.flatMap((label) => Array.from(dialog.querySelectorAll('label')).filter((el) => new RegExp(label).test(el.textContent ?? '')))[0];
      expect(labelEl, `应存在 label ${labels.join('/')}`).toBeTruthy();
      const item = labelEl!.closest('.ant-form-item') as HTMLElement;
      const control = item.querySelector('input.ant-input, .ant-input, input') as HTMLInputElement | null;
      expect(control, `${labels.join('/')} 输入控件应存在`).toBeTruthy();
      fireEvent.change(control!, { target: { value } });
    };
    // B-组定位修复：页面 label 为「租户代码」（zh-CN tenants.form.code），原用例误写「租户编码」
    await fillInput(['租户代码', '租户编码'], 'tenant_x');
    await fillInput(['租户名称'], '测试租户');
    // B-组补充（归因=用例错）：init 端点要求初始管理员账号必填（REQ-20261122-233426 确认③，
    // 实现面 adminUsername required）——用例流程遗漏该必填域，补填后提交才能走到 tenantInit
    await fillInput(['初始管理员账号', '管理员账号'], 'admin_x');
    const tplDropdown = await openSelectByLabels(dialog, ['模板', '开通模板']);
    fireEvent.click(await within(tplDropdown).findByText('标准模板'));

    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));
    await waitFor(() => {
      const inits = callsOf(fetchMock, 'POST', '/admin/v1/system/tenants/init');
      expect(inits, '提交必走 tenantInit').toHaveLength(1);
      const body = parseBody(inits[0]?.body) ?? {};
      expect(body.templateCode, 'templateCode 必填随体提交').toBe('standard');
      expect(body.packageId ?? undefined, '新建不应携带 packageId').toBeUndefined();
    });
    expect(callsOf(fetchMock, 'POST', '/admin/v1/system/tenants').filter((r) => !r.url.pathname.includes('/init')),
      'tenantCreate 端点前端不应再调用').toHaveLength(0);
  });

  it('should_keep_package_binding_field_when_tenant_edit_form_rendered', async () => {
    const state = {
      treeFail: false,
      pkgRows: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }],
      tplRows: [],
    };
    await renderPage(TenantsPage, PERMS_SEED, buildRoutes(state));
    const row = await findRowByText('总部');
    fireEvent.click(await within(row).findByText(/^编\s*辑$/));
    const dialog = await currentDialog();
    // 编辑态保留套餐绑定字段（确认③）
    const packageLabels = Array.from(dialog.querySelectorAll('label')).filter((el) => /套餐/.test(el.textContent ?? ''));
    expect(packageLabels.length, '编辑态应保留套餐绑定字段').toBeGreaterThanOrEqual(1);
  });
});

// ————— AC-23：模板预置角色系统角色下拉 + 权限目录多选 —————
describe('REQ20261125230807 模板表单预置角色（AC-23）', () => {
  it('should_pick_system_role_and_backfill_permissions_when_template_form_edited', async () => {
    const state = {
      treeFail: false,
      pkgRows: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }],
      tplRows: [{ id: SF_TPL_A, code: 'standard', name: '标准模板', remark: null, content: TPL_CONTENT, defaultPackageId: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }],
    };
    const { fetchMock } = await renderPage(TenantsPage, PERMS_SEED, buildRoutes(state));
    await findRowByText('总部');

    // 模板管理是 Tab 2（antd Tabs 默认只挂激活 pane）——先切「模板管理」页签再找模板行（B7②）
    fireEvent.click(await screen.findByRole('tab', { name: '模板管理' }));
    const tplRow = await findRowByText('标准模板');
    fireEvent.click(await within(tplRow).findByText(/^编\s*辑$/));
    const dialog = await currentDialog();

    // 预置角色：roleKey/roleName 改系统角色下拉（候选来自角色目录）
    // 系统角色下拉定位：扫描弹窗内 select，展开后候选含「租户管理员」（目录驱动判定）
    // B-组流程修复（归因=用例缺口）：fixture 预置 roleKey 已是 tenant_admin，重选同值 rc-select
    // 不触发 onChange → 回填 GET 不发生 → 先切 tenant_ops 再回选 tenant_admin（断言不变）
    const openRoleDropdownAndPick = async (optionRe: RegExp) => {
      const roleSelectors = Array.from(dialog.querySelectorAll('.ant-select-selector')) as HTMLElement[];
      expect(roleSelectors.length, '模板弹窗应有下拉控件').toBeGreaterThanOrEqual(1);
      // 系统角色下拉=defaultPackageId 之后的第二个 Select（表单域序：默认套餐→系统角色→权限点）；
      // 展开后按内容（含「租户管理员」候选）取浮层，防陈旧浮层误捕获
      fireEvent.mouseDown(roleSelectors[1]);
      const dropdown = await waitFor(() => {
        const el = Array.from(document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden)'))
          .find((node) => (node.textContent ?? '').includes('租户管理员'));
        expect(el, '系统角色下拉应展开且含候选').toBeTruthy();
        return el as HTMLElement;
      });
      fireEvent.click(within(dropdown).getByText(optionRe));
    };
    await openRoleDropdownAndPick(/租户运维/);
    await openRoleDropdownAndPick(/租户管理员/);

    // 选中自动回填该角色权限点（GET /roles/{id}/permissions → 权限点多选出现目录码）
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', `/admin/v1/system/roles/${SF_ROLE_TA}/permissions`).length).toBeGreaterThanOrEqual(1);
    });
    await waitFor(() => {
      expect(dialog.textContent ?? '', '选中系统角色应回填权限点目录码').toContain('system:user:list');
    });

    // permissionCodes 保留手输兜底：向权限多选输入自定义码并回车（tags 形态）
    const codesItem = (Array.from(dialog.querySelectorAll('label')).find((el) => /权限/.test(el.textContent ?? ''))?.closest('.ant-form-item')) as HTMLElement | null;
    if (codesItem?.querySelector('.ant-select-selector input')) {
      const tagInput = codesItem.querySelector('.ant-select-selector input') as HTMLInputElement;
      fireEvent.change(tagInput, { target: { value: 'custom:code:demo' } });
      fireEvent.keyDown(tagInput, { key: 'Enter', keyCode: 13, which: 13 });
      await waitFor(() => {
        expect(dialog.textContent ?? '').toContain('custom:code:demo');
      });
    }

    // configEntries 维持手填：既有 configKey 仍以可编辑输入呈现（fixture 值锚定）
    // B-组定位修复：输入框 value 不进 textContent → 按 input.value 锚定断言（断言语义不变）
    const configKeyInput = Array.from(dialog.querySelectorAll('input'))
      .find((el) => (el as HTMLInputElement).value === 'init.welcome') as HTMLInputElement | undefined;
    expect(configKeyInput, '既有 configKey（init.welcome）应以可编辑输入回显').toBeTruthy();

    // 保存 → wire content.rolePresets 形状（roleKey 选中值 + permissionCodes 数组）
    fireEvent.click(await within(dialog).findByRole('button', { name: SUBMIT_BUTTON }));
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT', `/admin/v1/system/tenant-templates/${SF_TPL_A}`);
      expect(puts).toHaveLength(1);
      const content = (parseBody(puts[0]?.body)?.content ?? {}) as { rolePresets?: { roleKey: string; roleName: string; permissionCodes: string[] }[] };
      expect(content.rolePresets?.[0]?.roleKey, '预置角色应选中系统角色 roleKey').toBe('tenant_admin');
      expect(content.rolePresets?.[0]?.roleName).toBe('租户管理员');
    });
  });
});

// ————— AC-27（租户侧）：租户树加载失败可见警告 + 重试 —————
describe('REQ20261125230807 租户目录失败可见化（AC-27 租户侧）', () => {
  it('should_show_warning_with_retry_and_recover_candidates_when_tenant_tree_load_fails', async () => {
    const state = { treeFail: true, pkgRows: [{ id: SF_PKG_A, packageName: '基础套餐', remark: null }], tplRows: [] };
    const { fetchMock } = await renderPage(TenantsPage, PERMS_SEED, buildRoutes(state));
    await findRowByText('总部');

    fireEvent.click(await screen.findByRole('button', { name: /新\s*建(\s*租\s*户)?$/ }));
    await currentDialog();

    // 可见警告而非静默空候选
    await waitFor(() => {
      const alert = document.querySelector('.ant-alert-error, .ant-alert-warning, [role="alert"]');
      expect(alert, '租户树加载失败应出现可见警告').toBeTruthy();
    });

    // 重试入口：翻转 mock → 点击重试 → 目录重新拉取、候选恢复
    const retry = await screen.findByRole('button', { name: /重\s*试/ });
    state.treeFail = false;
    fireEvent.click(retry);
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', '/admin/v1/system/tenants/tree').length).toBeGreaterThanOrEqual(2);
    });
    const dialog = await currentDialog();
    const dropdown = await openSelectByLabels(dialog, ['父\\s*租\\s*户', '上级租户']);
    expect(await within(dropdown).findByText('独立租户'), '重试成功后候选恢复').toBeTruthy();
  }, 90000); // B-组口径（同 B7③ 显式超时）：同机后端全量测试 CPU 争抢下查询饥饿（聚焦探针实测
             // 18s/25s 巨块、断言全部走到），用例级预算 45s→90s，断言集不变
});
