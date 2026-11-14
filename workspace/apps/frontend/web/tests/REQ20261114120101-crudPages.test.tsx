/**
 * REQ-20261114-120101 AC-1：既有管理页迁移 CrudTable/CrudFormModal 后行为等价回归。
 *
 * 覆盖页（契约确认记录③必迁清单）：Tenants / Roles / Permissions / Menus / Dicts / Audit。
 * Apps/Releases 为业务页，实施时豁免须记录（契约③），不在本套件内。
 *
 * 行为等价清单（AC-1）：列表渲染 / 搜索触发回页首 / 新建 Modal 提交 / 编辑回填 / 删除确认 / 权限显隐。
 * 装配照既有范本 web/tests/REQ20261114071607-usersPage.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 权限播种 + <AntApp> + testTimeout ≥15000ms；关闭态断言一律数据驱动（071607 裁决⑪）。
 *
 * 【页面差异处理（契约/DTO 推导，非猜测实现）】
 *   - 租户无 DELETE 权限点（SystemPermissions 仅 list/create/query/update）→ 租户页不测删除。
 *   - Audit 为只读审计查询（事件由后端写入，无 UI 新建/编辑/删除）→ 仅测列表；搜索/筛选形态未知不测。
 *   - Menus 为树形管理页 → 不测搜索（树页无关键字搜索）。
 *   - DTO 形状 = platform-system-api / platform-infra-api Admin*View（Long→string、枚举 wire 名、时间 ISO-8601）。
 *
 * 【表面口径（round1 部分裁决钉死）】
 *   A-35 六页均为命名导出（对齐 UsersPage 惯例），路径 web/src/pages/<Key>Page.tsx
 *   A-32 搜索交互对齐 usersPage：占位符含「搜索」的输入框 + Enter 提交（CrudTable 统一行为）
 *   A-33 列表请求携带 page=1（CrudTable 分页参数口径）
 *   A-34 Dicts 页存在类型/条目两类请求：类型域端点 /admin/v1/system/dict-types*（frontend round0
 *       装配修正 pathPart='dict'）；数字 id 子资源路径回空页，非数字子路径按列表返回
 *   A-36 编辑回填承载控件为 Input/TextArea（含只读排除）；Menus 新建维持首个可用文本框行为式
 *       （round0 已通过），失败例 1/2 页面改按 label 语义填字段（round1 打回单）
 *   播种归一（round1 裁决⑤）：me.permissions = 后端三段式码（SystemPermissions.java 口径），
 *   双拼写播种废除；行删除 id 走路径参数（usersPage 口径，弱匹配包含断言）
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ComponentType } from 'react';
import { App as AntApp } from 'antd';
import { TenantsPage } from '../src/pages/TenantsPage';
import { RolesPage } from '../src/pages/RolesPage';
import { PermissionsPage } from '../src/pages/PermissionsPage';
import { MenusPage } from '../src/pages/MenusPage';
import { DictsPage } from '../src/pages/DictsPage';
import { AuditPage } from '../src/pages/AuditPage';
import { useAuthStore, useMeStore } from '@acme/core';

// Dicts 为主从双卡布局（双 Table + 双 Modal），渲染预算高于单表页；15000 下 Dicts edit 已贴线超时（装配放宽）
vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量（usersPage 口径：两字按钮 autoInsertSpace 兼容） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const DELETE_BUTTON = /删\s*除/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— 页面描述符（DTO 形状 = Admin*View wire 口径） —————
type CreateSpec = {
  perm: string[];
  /** label 式（失败例 1/2，round1）：按字段语义逐个 getByLabelText 填值 */
  fields?: { label: string; value: string }[];
  /** 必填 Select（Permissions「类型」，round1/delta-3）：option=选项可访问名（显示名，用于点选），value=提交 wire 值（缺省同 option） */
  select?: { label: string; option: string; value?: string };
  /** 行为式（Menus，round0 已通过）：填弹窗内首个可用文本框的值 */
  value?: string;
};

type PageSpec = {
  key: string;
  Component: ComponentType;
  pathPart: string;
  rows: Record<string, unknown>[];
  anchors: string[];                    // 列表断言/行定位候选
  create: CreateSpec | null;
  edit: { perm: string[]; prefill: string; update: string } | null;
  remove: { perm: string[]; rowAnchor: string; id: string; stillAnchor: string } | null;
  search: boolean;
};

const SPECS: PageSpec[] = [
  {
    key: 'Tenants',
    Component: TenantsPage,
    pathPart: 'tenants',
    rows: [
      { id: '3001', tenantCode: 'acme', tenantName: '爱思蜜科技', status: 'ENABLED', remark: '主租户', createdAt: '2026-01-01T00:00:00Z', version: '0' },
      { id: '3002', tenantCode: 'beta', tenantName: '贝塔租户', status: 'ENABLED', remark: '', createdAt: '2026-01-02T00:00:00Z', version: '0' },
    ],
    anchors: ['爱思蜜科技', 'acme'],
    // 租户无 DELETE 权限点（SystemPermissions）→ 不测删除
    // create 按字段语义填（round1 失败例 1）：租户代码小写模式校验 + 租户名称必填
    create: {
      perm: ['system:tenant:create'],
      fields: [
        { label: '租户代码', value: 'xin-zuhu-1' },
        { label: '租户名称', value: '新租户甲' },
      ],
    },
    edit: { perm: ['system:tenant:update'], prefill: '贝塔租户', update: '贝塔改名' },
    remove: null,
    search: true,
  },
  {
    key: 'Roles',
    Component: RolesPage,
    pathPart: 'roles',
    rows: [
      { id: '3101', parentId: '0', roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED', builtIn: true, remark: '', createdAt: '2026-01-01T00:00:00Z', children: [], version: '0' },
      { id: '3102', parentId: '0', roleCode: 'ops', roleName: '运维员', status: 'ENABLED', builtIn: false, remark: '', createdAt: '2026-01-02T00:00:00Z', children: [], version: '0' },
    ],
    anchors: ['超级管理员', 'super_admin'],
    // create 按字段语义填（round1 失败例 1）：角色代码小写合法值 + 角色名称必填
    create: {
      perm: ['system:role:create'],
      fields: [
        { label: '角色代码', value: 'xin-jiao-se-1' },
        { label: '角色名称', value: '新角色甲' },
      ],
    },
    edit: { perm: ['system:role:update'], prefill: '运维员', update: '运维员改名' },
    // 内置角色不可删 → 用非内置行
    remove: { perm: ['system:role:delete'], rowAnchor: '运维员', id: '3102', stillAnchor: '超级管理员' },
    search: true,
  },
  {
    key: 'Permissions',
    Component: PermissionsPage,
    pathPart: 'permissions',
    rows: [
      { id: '3201', permissionCode: 'system:user:list', permissionName: '用户查询', permissionType: 'API', httpMethod: 'GET', httpPath: '/admin/v1/system/users', status: 'ENABLED', createdAt: '2026-01-01T00:00:00Z', version: '0' },
      // fixture 文案避开 ADD_BUTTON 正则（round1 失败例 3）：『用户新建』含『新建』会撞 /新\s*[建增]/
      { id: '3202', permissionCode: 'system:user:create', permissionName: '用户列表', permissionType: 'API', httpMethod: 'POST', httpPath: '/admin/v1/system/users', status: 'ENABLED', createdAt: '2026-01-02T00:00:00Z', version: '0' },
    ],
    anchors: ['用户查询', 'system:user:list'],
    // create 按字段语义填（round1 失败例 2）：权限代码/权限名称 + 必填 Select「类型」（选项 API）
    create: {
      perm: ['system:permission:create'],
      fields: [
        { label: '权限代码', value: 'system:test:new' },
        { label: '权限名称', value: '新权限点甲' },
      ],
      // delta-3：实现选项 label='API（接口级）'/value='API'（迁移前基线，实现禁改）——
      // 点选按可访问名（显示名）匹配，POST body 断言按 wire 值
      select: { label: '类型', option: 'API（接口级）', value: 'API' },
    },
    edit: { perm: ['system:permission:update'], prefill: '用户列表', update: '用户列表改名' },
    remove: { perm: ['system:permission:delete'], rowAnchor: '用户列表', id: '3202', stillAnchor: '用户查询' },
    search: false, // AC-1 等价口径：迁移前本页无关键字搜索（装配修正，见交付说明）
  },
  {
    key: 'Menus',
    Component: MenusPage,
    pathPart: 'menus',
    rows: [
      {
        id: '3301', parentId: '0', menuName: '系统管理', menuType: 'DIRECTORY', path: '/system', component: null, icon: null,
        sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', version: '0',
        children: [
          { id: '3302', parentId: '3301', menuName: '用户中心', menuType: 'MENU', path: '/users', component: 'users', icon: null, sortOrder: 1, visible: true, permissionCode: 'system:user:list', status: 'ENABLED', children: [], version: '0' },
        ],
      },
      { id: '3303', parentId: '0', menuName: '报表中心', menuType: 'MENU', path: '/reports', component: null, icon: null, sortOrder: 2, visible: true, permissionCode: null, status: 'ENABLED', children: [], version: '0' },
    ],
    anchors: ['系统管理', '报表中心'],
    // Menus create 维持 round0 已通过的首个可用文本框行为式（round1 未列失败，不改填法）
    create: { perm: ['system:menu:create'], value: '新菜单X' },
    edit: { perm: ['system:menu:update'], prefill: '报表中心', update: '报表中心改名' },
    remove: { perm: ['system:menu:delete'], rowAnchor: '报表中心', id: '3303', stillAnchor: '系统管理' },
    search: false, // 树形管理页无关键字搜索
  },
  {
    key: 'Dicts',
    Component: DictsPage,
    // 弱匹配子串取 'dict'：既有类型域端点为 /admin/v1/system/dict-types*（'dicts' 不命中，装配修正）
    pathPart: 'dict',
    rows: [
      { id: '3401', dictCode: 'user_status', dictName: '用户状态', status: 'ENABLED', remark: '', createdAt: '2026-01-01T00:00:00Z', version: '0' },
      { id: '3402', dictCode: 'gender', dictName: '性别字典', status: 'ENABLED', remark: '', createdAt: '2026-01-02T00:00:00Z', version: '0' },
    ],
    anchors: ['用户状态', 'user_status'],
    // create 按字段语义填（round1 失败例 1）：类型代码小写合法值 + 类型名称必填
    create: {
      perm: ['system:dict:create'],
      fields: [
        { label: '类型代码', value: 'xin-zi-dian-1' },
        { label: '类型名称', value: '新字典甲' },
      ],
    },
    edit: { perm: ['system:dict:update'], prefill: '性别字典', update: '性别字典改名' },
    remove: { perm: ['system:dict:delete'], rowAnchor: '性别字典', id: '3402', stillAnchor: '用户状态' },
    search: false, // AC-1 等价口径：迁移前类型表无关键字搜索（装配修正，见交付说明）
  },
  {
    key: 'Audit',
    Component: AuditPage,
    pathPart: 'audit',
    rows: [
      { id: '9001', eventId: 'evt-1', traceId: 'trace-1', spanId: 'span-1', tenantId: 'default', operatorId: '42', channel: 'B', action: 'infra.audit.list', targetType: 'auditEvent', targetId: null, content: '查询审计', occurredAt: '2026-10-26T08:30:00Z', createdAt: '2026-10-26T08:30:00Z' },
      { id: '9002', eventId: 'evt-2', traceId: 'trace-2', spanId: 'span-2', tenantId: 'default', operatorId: '42', channel: 'B', action: 'file.upload', targetType: 'sysFile', targetId: '9003', content: '上传文件', occurredAt: '2026-10-26T09:00:00Z', createdAt: '2026-10-26T09:00:00Z' },
    ],
    anchors: ['查询审计', 'infra.audit.list'],
    create: null, edit: null, remove: null, // 只读审计查询：无 UI 写路径
    search: false, // 筛选形态未知（时间/类型维度），不假设
  },
];

// ————— fetch 边界 mock（每 spec 独立状态） —————
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

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

const lastQuery = (fetchMock: ReturnType<typeof vi.fn>, pathPart: string) =>
  callsOf(fetchMock, 'GET', pathPart).at(-1)?.url.searchParams ?? null;

const setupApi = (spec: PageSpec) => {
  const state = { rows: spec.rows.map((r) => ({ ...r })) };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (!p.includes(spec.pathPart)) return envelope(null);
    if (r.method === 'GET') {
      // Dicts 页类型/条目双请求（A-34，类型域端点为 /admin/v1/system/dict-types*）：
      // 数字 id 子资源路径（/dict-types/{id}/items 等，pathPart='dict' 时允许 -types 后缀）回空页；
      // 非数字子路径（如 /menus/tree）仍按列表返回，避免误判为深层
      const deep = new RegExp(`/${spec.pathPart}[^/]*\\/\\d`).test(p);
      return envelope({ records: deep ? [] : state.rows, total: deep ? 0 : state.rows.length });
    }
    if (r.method === 'POST') {
      const body = parseBody(r.body);
      const created = { id: '9901', ...body };
      state.rows = [...state.rows, created];
      return envelope(created);
    }
    if (r.method === 'PUT' || r.method === 'PATCH') {
      const body = parseBody(r.body);
      const id = p.split('/').filter(Boolean).pop() ?? '';
      state.rows = state.rows.map((item) => (item.id === id ? { ...item, ...body } : item));
      return envelope(state.rows.find((item) => item.id === id) ?? null);
    }
    if (r.method === 'DELETE') {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      state.rows = state.rows.filter((item) => item.id !== id);
      return envelope(null); // 等价口径：软删/幂等成功
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

const renderPage = async (spec: PageSpec, permissions: string[]) => {
  await seedMe(permissions);
  const api = setupApi(spec);
  render(<AntApp><spec.Component /></AntApp>);
  return api;
};

const findByAnyText = async (candidates: string[]) => {
  for (const c of candidates) {
    try {
      return await screen.findByText(c, {}, { timeout: 3000 });
    } catch {
      // 候选逐个尝试（列选择未知 → 多锚兜底）
    }
  }
  throw new Error(`未找到任何行锚文本: ${candidates.join(' / ')}`);
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

/** 对话框内文本类输入框（Input/TextArea/number；排除 select 搜索框/checkbox/hidden，A-36） */
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

// ————— 参数化套件：每页断言 AC-1 等价行为清单的可适用子集 —————
// 播种归一三段式（round1 裁决⑤：me.permissions = SystemPermissions.java 常量口径）
function listPermsOf(s: PageSpec): string[] {
  switch (s.key) {
    case 'Tenants': return ['system:tenant:list'];
    case 'Roles': return ['system:role:list'];
    case 'Permissions': return ['system:permission:list'];
    case 'Menus': return ['system:menu:list'];
    case 'Dicts': return ['system:dict:list'];
    default: return ['infra:audit:list'];
  }
}

for (const spec of SPECS) {
  const listPerms = listPermsOf(spec);

  describe(`REQ20261114120101 ${spec.key} 迁移 CRUD 范本回归（AC-1）`, () => {
    it(`should_render_list_rows_when_${spec.key}_loads`, async () => {
      const { fetchMock } = await renderPage(spec, listPerms);
      await findByAnyText(spec.anchors);
      // 列表请求携带分页参数（A-33）
      expect(lastQuery(fetchMock, spec.pathPart)?.get('page')).toBe('1');
    });

    if (spec.search) {
      it(`should_reset_to_first_page_with_keyword_when_${spec.key}_search_submitted`, async () => {
        const { fetchMock } = await renderPage(spec, listPerms);
        await findByAnyText(spec.anchors);
        const input = screen.getAllByPlaceholderText(/搜\s*索/)[0] as HTMLInputElement;
        fireEvent.change(input, { target: { value: 'zz-keyword' } });
        fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
        await waitFor(() => {
          const query = lastQuery(fetchMock, spec.pathPart);
          expect([...(query?.values() ?? [])]).toContain('zz-keyword');
          expect(query?.get('page')).toBe('1');
        });
      });
    }

    if (spec.create) {
      const createSpec = spec.create;
      it(`should_submit_create_modal_when_${spec.key}_add_and_confirm_clicked`, async () => {
        const { fetchMock } = await renderPage(spec, [...listPerms, ...createSpec.perm]);
        await findByAnyText(spec.anchors);
        fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
        const dialog = await screen.findByRole('dialog');

        // 填表：label 式（失败例 1/2，round1）按字段语义逐个填；行为式（Menus）填首个可用文本框
        if (createSpec.fields) {
          for (const f of createSpec.fields) {
            fireEvent.change(within(dialog).getByLabelText(f.label), { target: { value: f.value } });
          }
        } else {
          const box = dialogTextboxes(dialog)[0];
          fireEvent.change(box!, { target: { value: createSpec.value! } });
        }
        // 必填 Select（失败例 2，round1；delta-4 换思路修复）：antd5 rc-select virtual 默认开启，
        // role=option 命中的是隐藏 a11y 镜像（无 onClick，点了死节点）——按显示名文本点选可见
        // 选项内容节点（与 dictSelect emit 用例同型）；提交 wire 值经 select.value 承载
        if (createSpec.select) {
          fireEvent.mouseDown(within(dialog).getByLabelText(createSpec.select.label));
          fireEvent.click(await screen.findByText(createSpec.select.option));
          // 选中回显断言（失败前置报错更快，同 dictSelect 口径）：未选中则在此即失败
          await waitFor(() => {
            const item = document.querySelector('.ant-select-selection-item');
            expect(item?.textContent ?? '').toContain(createSpec.select?.option ?? '');
          });
        }
        fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

        // 提交体包含全部填入值（label 式含各字段值 + Select 提交值；行为式含首个文本框值）
        const expectedValues = createSpec.fields
          ? [...createSpec.fields.map((f) => f.value),
             ...createSpec.select ? [createSpec.select.value ?? createSpec.select.option] : []]
          : [createSpec.value!];
        await waitFor(() => {
          const posts = callsOf(fetchMock, 'POST', spec.pathPart);
          expect(posts).toHaveLength(1);
          const bodyJson = JSON.stringify(parseBody(posts[0]?.body));
          for (const v of expectedValues) {
            expect(bodyJson).toContain(v);
          }
        });
        // 关闭态数据驱动：提交成功后的 reload 二次 GET（071607 裁决⑪）
        await waitFor(() => {
          expect(callsOf(fetchMock, 'GET', spec.pathPart).length).toBeGreaterThanOrEqual(2);
        });
      });
    }

    if (spec.edit) {
      const editSpec = spec.edit;
      it(`should_prefill_and_submit_update_when_${spec.key}_row_edit_clicked`, async () => {
        const { fetchMock } = await renderPage(spec, [...listPerms, ...editSpec.perm]);
        const rowEl = await findRowByText(editSpec.prefill);
        fireEvent.click(await within(rowEl).findByText(EDIT_BUTTON));
        const dialog = await screen.findByRole('dialog');
        // 回填行为式断言：弹窗文本框承载该行既有值（A-36）
        const box = await waitFor(() => {
          const found = dialogTextboxes(dialog).find((el) => el.value === editSpec.prefill);
          expect(found).toBeTruthy();
          return found as HTMLInputElement | HTMLTextAreaElement;
        });
        fireEvent.change(box, { target: { value: editSpec.update } });
        fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));
        await waitFor(() => {
          const updates = [...callsOf(fetchMock, 'PUT', spec.pathPart), ...callsOf(fetchMock, 'PATCH', spec.pathPart)];
          expect(updates).toHaveLength(1);
          expect(JSON.stringify(parseBody(updates[0]?.body))).toContain(editSpec.update);
        });
      });
    }

    if (spec.remove) {
      const removeSpec = spec.remove;
      it(`should_delete_row_after_confirm_when_${spec.key}_row_delete_clicked`, async () => {
        const { fetchMock } = await renderPage(spec, [...listPerms, ...removeSpec.perm]);
        const rowEl = await findRowByText(removeSpec.rowAnchor);
        fireEvent.click(await within(rowEl).findByText(DELETE_BUTTON));
        fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));
        await waitFor(() => {
          const deletes = callsOf(fetchMock, 'DELETE', spec.pathPart);
          expect(deletes).toHaveLength(1);
          expect(deletes[0]?.url.pathname).toContain(removeSpec.id);
        });
        await waitFor(() => expect(screen.queryByText(removeSpec.rowAnchor)).toBeNull());
        expect(screen.getByText(removeSpec.stillAnchor)).toBeTruthy();
      });
    }

    if (spec.create || spec.edit || spec.remove) {
      it(`should_hide_unauthorized_actions_when_${spec.key}_permission_missing`, async () => {
        await renderPage(spec, listPerms); // 仅 list 权限
        await findByAnyText(spec.anchors);
        expect(screen.queryByText(ADD_BUTTON)).toBeNull();
        const rowEl = await findRowByText(spec.anchors[0]);
        expect(within(rowEl).queryByText(EDIT_BUTTON)).toBeNull();
        expect(within(rowEl).queryByText(DELETE_BUTTON)).toBeNull();
      });
    }
  });
}
