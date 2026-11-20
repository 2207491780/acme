/**
 * REQ-20261121-054613 套餐表单「能力」checkbox 组契约用例（web 面）。
 *
 * 覆盖（契约 AC-9 + AC-11，§确认记录⑥）：
 *   - AC-9 should_manage_capabilities_in_package_form_when_operated：表单组全链路——
 *     能力组渲染（目录名直出）→ 新建勾选（提交随 capabilities 全量替换）→
 *     编辑回显（详情 capabilities 驱动勾选态）→ 改配提交（PUT 全量替换）。
 *   - AC-11 should_cover_capability_strings_in_zh_and_en：zh/en 双语无裸 key
 *     （能力组 label/按钮/占位符；键名归 frontend，文案宽容匹配——zh 消失/en 出现/
 *     无点号资源键形状；目录能力名与菜单名为后端数据样例，按语言配套 fixture 不计入 UI 断言）。
 *
 * 装配照既有范本 web/tests/REQ20261121022910-packagesPage.test.tsx（fetch stubGlobal 精确
 * pathname 路由 + useMeStore 权限播种 + <AntApp> 包裹 + matchMedia/ResizeObserver 桩 +
 * i18n 切换口径）；交互防坑：checkbox 打结构节点（pitfall 9——点 .ant-checkbox-wrapper 内
 * input[type=checkbox]）；文案宽容匹配（pitfall 14）；testTimeout 45s 文件级（TL 钉死，pitfall 15）。
 *
 * 【表面假定（低风险，失败即交 TL 裁决）】
 *   A-1 能力组为 antd Form.Item（label i18n 键 zh=能力/en=Capabilities）内 Checkbox 组，
 *       选项 label=能力名（码副文本不计断言）；选项渲染于 .ant-checkbox-wrapper 内可点
 *       input[type=checkbox]。
 *   A-2 能力组数据源 = GET /admin/v1/system/capabilities（systemApi.capabilityList，契约变更清单）；
 *       表单提交随 capabilities: string[]（勾选集），编辑回显数据源 = 套餐详情 capabilities。
 *   A-3 页面/弹窗其余形态（名称/备注/菜单树/列表行）沿用 REQ-022910 既有范本口径。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { i18n, useAuthStore, useMeStore, type AdminCapabilityView, type AdminMenuView, type AdminTenantPackageView } from '@acme/core';
import { TenantPackagesPage } from '../src/pages/TenantPackagesPage';

// testTimeout 45s（TL 钉死：jsdom 表单类用例文件级余量，pitfall 15 抖动判别口径）
vi.setConfig({ testTimeout: 45000 });

// ————— 行为常量（crudPages/packagesPage 口径：两字按钮 autoInsertSpace 兼容） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const NAME_LABEL = /套\s*餐\s*名\s*称/;
const CJK = /[\u4e00-\u9fff]/;
/** 裸 key 扫描形状：点号分隔资源键（如 tenantPackages.form.capabilities）。 */
const BARE_KEY = /^[A-Za-z][\w-]*\.[\w-]+(\.[\w-]+)+$/;

const PERMS = [
  'system:tenant-package:list', 'system:tenant-package:create',
  'system:tenant-package:update', 'system:tenant-package:delete',
];

/** 契约钉死能力码（V25 种子锚点）：目录样例码面固定，名/语言形态随 fixture 注入。 */
const WORKFLOW_CODE = 'platform:workflow';
const SCHEDULER_CODE = 'platform:scheduler';

// ————— fixtures（version=乐观锁 wire 字符串口径，LongToStringModule 全局生效） —————
const PACKAGE_ROWS: AdminTenantPackageView[] = [
  {
    id: '1', packageName: '套餐甲', remark: '承载能力回显演示的套餐', menuCount: 2, tenantCount: 1,
    version: '0', createdAt: '2026-01-03T00:00:00Z', updatedAt: '2026-01-03T00:00:00Z',
  },
  {
    id: '2', packageName: '套餐乙', remark: null, menuCount: 0, tenantCount: 0,
    version: '0', createdAt: '2026-01-04T00:00:00Z', updatedAt: '2026-01-04T00:00:00Z',
  },
];

/** 菜单树（Tree 勾选数据源）：目录 1 → 叶 11（挂按钮 21）+ 叶 12；本用例不勾选菜单，
 *  名称语言随 fixture 注入（en 腿配英文样例名，避免数据中文污染 en 无裸 key 扫描）。 */
const menuTree = (dirName: string, usersName: string, deptsName: string, btnName: string): AdminMenuView[] => [
  {
    id: 1, parentId: 0, menuName: dirName, menuType: 'DIRECTORY', path: null, component: null, icon: null,
    sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', version: '0',
    children: [
      {
        id: 11, parentId: 1, menuName: usersName, menuType: 'MENU', path: '/users', component: null, icon: null,
        sortOrder: 1, visible: true, permissionCode: 'system:user:list', status: 'ENABLED', version: '0',
        children: [
          {
            id: 21, parentId: 11, menuName: btnName, menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 1, visible: true, permissionCode: 'system:user:create', status: 'ENABLED', version: '0',
            children: [],
          },
        ],
      },
      {
        id: 12, parentId: 1, menuName: deptsName, menuType: 'MENU', path: '/depts', component: null, icon: null,
        sortOrder: 2, visible: true, permissionCode: 'system:dept:list', status: 'ENABLED', version: '0',
        children: [],
      },
    ],
  },
];

/** 能力目录（Checkbox 组数据源）：码面=V25 种子锚点；名随语言 fixture 注入（能力名是后端数据非 i18n 文案）。 */
const capabilityCatalog = (workflowName: string, schedulerName: string): AdminCapabilityView[] => [
  { id: '1', capabilityCode: WORKFLOW_CODE, capabilityName: workflowName, status: 'ENABLED' },
  { id: '2', capabilityCode: SCHEDULER_CODE, capabilityName: schedulerName, status: 'ENABLED' },
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

const seedMe = async (permissions: string[]): Promise<void> => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser, roles: ['super_admin'], permissions, menus: [],
  })));
  await useMeStore.getState().load();
};

interface PackagesSpec {
  /** 能力目录样例（GET /system/capabilities 响应）。 */
  catalog: AdminCapabilityView[];
  /** 菜单树样例（GET /system/menus 响应）。 */
  menuTree: AdminMenuView[];
  /** 套餐详情 capabilities（编辑回显数据源；未配置 id 走空集）。 */
  detailCapabilities?: Record<string, string[]>;
}

interface PackagesState {
  posts: { body: Record<string, unknown>; url: URL }[];
  puts: { body: Record<string, unknown>; url: URL }[];
}

/** 套餐五端点 + 菜单树 + 能力目录 + me 精确 pathname 路由（伴随请求回空信封）。 */
const setupPackagesApi = (spec: PackagesSpec) => {
  const state: PackagesState = { posts: [], puts: [] };
  const rows = PACKAGE_ROWS.map((row) => ({ ...row }));
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [] });
    }
    if (p === '/admin/v1/system/menus' && r.method === 'GET') {
      return envelope(spec.menuTree);
    }
    if (p === '/admin/v1/system/capabilities' && r.method === 'GET') {
      return envelope(spec.catalog);
    }
    if (p === '/admin/v1/system/tenant-packages') {
      if (r.method === 'GET') return envelope({ records: rows, total: rows.length });
      if (r.method === 'POST') {
        const body = parseBody(r.body);
        state.posts.push({ body, url: r.url });
        const created: AdminTenantPackageView = {
          id: '9901', packageName: String(body.packageName ?? ''), remark: (body.remark as string | null) ?? null,
          menuCount: Array.isArray(body.menuIds) ? body.menuIds.length : 0, tenantCount: 0,
          version: '0', createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z',
        };
        rows.push(created);
        return envelope(created);
      }
      return envelope(null);
    }
    const packageMatch = /^\/admin\/v1\/system\/tenant-packages\/([^/]+)$/.exec(p);
    if (packageMatch) {
      const id = packageMatch[1] as string;
      if (r.method === 'GET') {
        const target = rows.find((row) => row.id === id);
        if (!target) return envelope(null, 404);
        return envelope({
          ...target,
          menuIds: target.id === '1' ? [11, 21] : [],
          capabilities: spec.detailCapabilities?.[id] ?? [],
        });
      }
      if (r.method === 'PUT') {
        const body = parseBody(r.body);
        state.puts.push({ body, url: r.url });
        const updated = rows
          .map((row) => (row.id === id
            ? {
              ...row,
              packageName: String(body.packageName ?? row.packageName),
              remark: body.remark === undefined ? row.remark : (body.remark as string | null),
              menuCount: Array.isArray(body.menuIds) ? body.menuIds.length : row.menuCount,
              updatedAt: '2026-06-02T00:00:00Z',
            }
            : row))
          .find((row) => row.id === id);
        return envelope(updated ?? null);
      }
      return envelope(null);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { state };
};

const renderPage = async (spec: PackagesSpec): Promise<{ state: PackagesState }> => {
  await seedMe(PERMS);
  const api = setupPackagesApi(spec);
  render(<AntApp><TenantPackagesPage /></AntApp>);
  return api;
};

const fillField = async (dialog: HTMLElement, label: RegExp, value: string): Promise<void> => {
  fireEvent.change(within(dialog).getByLabelText(label), { target: { value } });
};

/** 能力 checkbox（antd v5 Checkbox 组）：定位能力名文案所在 .ant-checkbox-wrapper，点其
 *  input[type=checkbox] 结构节点（pitfall 9——交互打结构节点非文案节点）。 */
const capabilityCheckbox = async (dialog: HTMLElement, name: string): Promise<HTMLInputElement> => {
  const text = await within(dialog).findByText(name, {}, { timeout: 5000 });
  const wrapper = text.closest('.ant-checkbox-wrapper') as HTMLElement | null;
  expect(wrapper, `能力「${name}」应渲染为 .ant-checkbox-wrapper checkbox 项`).toBeTruthy();
  const input = wrapper?.querySelector('input[type="checkbox"]') as HTMLInputElement | null;
  expect(input, `能力「${name}」checkbox input 结构节点应存在`).toBeTruthy();
  return input as HTMLInputElement;
};

/** 能力组 Form label（A-1：label 元素承载 i18n 文案；hint/extra 为 div 不计入）。 */
const capabilityGroupLabel = async (dialog: HTMLElement, labelPattern: RegExp): Promise<void> => {
  const matches = within(dialog).getAllByText(labelPattern)
    .filter((el) => el.tagName === 'LABEL');
  expect(matches.length, `能力组 Form label（${labelPattern}）应存在`).toBeGreaterThan(0);
};

/** 打开行内动作：定位 anchor 文案所在行后在该行内点按 name 匹配的按钮（packagesPage 范本）。 */
const rowAction = async (anchor: string, action: RegExp): Promise<HTMLElement> => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  const row = cell.closest('tr') as HTMLElement;
  expect(row, `行 ${anchor} 应存在`).toBeTruthy();
  fireEvent.click(within(row).getByText(action));
  return row;
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

// ————— AC-9：套餐表单能力组全链路 —————

describe('REQ20261121054613 套餐表单能力组（AC-9）', () => {
  it('should_manage_capabilities_in_package_form_when_operated', async () => {
    const { state } = await renderPage({
      catalog: capabilityCatalog('工作流', '调度中心'),
      menuTree: menuTree('系统管理', '用户管理', '部门管理', '新建用户'),
      detailCapabilities: { '1': [WORKFLOW_CODE] },
    });

    // —— 列表基线：两行样例渲染（弹窗动作前置锚点） ——
    await screen.findByText('套餐甲', {}, { timeout: 5000 });
    expect(screen.getByText('套餐乙')).toBeTruthy();

    // —— 新建：能力组显示目录名 → 勾选「工作流」→ POST capabilities=[platform:workflow] ——
    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const createDialog = await screen.findByRole('dialog');
    await capabilityGroupLabel(createDialog, /^能\s*力$/);
    const workflowInput = await capabilityCheckbox(createDialog, '工作流');
    expect(workflowInput.checked, '新建弹窗初始无勾选（缺省=空集）').toBe(false);
    fireEvent.click(workflowInput);
    await waitFor(() => expect(workflowInput.checked).toBe(true));
    const schedulerInput = await capabilityCheckbox(createDialog, '调度中心');
    expect(schedulerInput.checked, '未勾选能力保持空态').toBe(false);

    await fillField(createDialog, NAME_LABEL, '集成能力套餐');
    fireEvent.click(await within(createDialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.posts).toHaveLength(1));
    const post = state.posts[0] as { body: Record<string, unknown>; url: URL };
    expect(post.url.pathname).toBe('/admin/v1/system/tenant-packages');
    expect(post.body.packageName).toBe('集成能力套餐');
    expect(post.body.capabilities, '勾选能力码应随 capabilities 提交（全量替换语义）')
      .toEqual([WORKFLOW_CODE]);
    await screen.findByText(/已创建/, {}, { timeout: 5000 });

    // —— 编辑：详情回显（platform:workflow 预勾）→ 改配为 scheduler → PUT 全量替换 ——
    await rowAction('套餐甲', EDIT_BUTTON);
    const editDialog = await screen.findByRole('dialog');
    await waitFor(() => {
      expect((within(editDialog).getByLabelText(NAME_LABEL) as HTMLInputElement).value).toBe('套餐甲');
    });
    // 详情异步装载：回显断言每轮重查 checkbox 结构节点（antd 重渲染可能替换节点，
    // 闭包持旧节点会把失联节点的 checked 永久冻结在 false）
    await waitFor(async () => {
      const input = await capabilityCheckbox(editDialog, '工作流');
      expect(input.checked, '详情 capabilities=[platform:workflow] 应回显为勾选态（编辑回显）')
        .toBe(true);
    });
    const editScheduler = await capabilityCheckbox(editDialog, '调度中心');
    expect(editScheduler.checked, '详情未含能力保持空态').toBe(false);

    fireEvent.click(editScheduler);
    await waitFor(async () => {
      const input = await capabilityCheckbox(editDialog, '调度中心');
      expect(input.checked, '勾选调度中心后 checkbox 应置为勾选态').toBe(true);
    });
    const editWorkflow = await capabilityCheckbox(editDialog, '工作流');
    fireEvent.click(editWorkflow);
    await waitFor(async () => {
      const input = await capabilityCheckbox(editDialog, '工作流');
      expect(input.checked, '取消勾选工作流后 checkbox 应置为空态').toBe(false);
    });

    fireEvent.click(await within(editDialog).findByRole('button', { name: CONFIRM_BUTTON }));
    await waitFor(() => expect(state.puts).toHaveLength(1));
    const put = state.puts[0] as { body: Record<string, unknown>; url: URL };
    expect(put.url.pathname).toBe('/admin/v1/system/tenant-packages/1');
    expect(put.body.capabilities, '改配后提交=当前勾选集（全量替换）').toEqual([SCHEDULER_CODE]);
    await screen.findByText(/已更新/, {}, { timeout: 5000 });
  });
});

// ————— AC-11：zh/en 双语无裸 key —————

describe('REQ20261121054613 能力组 i18n（AC-11）', () => {
  /** 弹窗 chrome 文案收集：按钮/输入占位/label（含 checkbox wrapper label）。 */
  const dialogTexts = (dialog: HTMLElement): string[] => {
    const texts: string[] = [];
    dialog.querySelectorAll('button').forEach((button) => texts.push((button.textContent ?? '').trim()));
    dialog.querySelectorAll('input, textarea').forEach((el) => {
      const placeholder = el.getAttribute('placeholder');
      if (placeholder) texts.push(placeholder);
    });
    dialog.querySelectorAll('label').forEach((label) => texts.push((label.textContent ?? '').trim()));
    return texts.filter((text) => text.length > 0);
  };

  it('should_cover_capability_strings_in_zh_and_en', async () => {
    // —— zh 腿：能力组 label=能力（zh i18n 文案），目录样例名=zh 数据 ——
    await renderPage({
      catalog: capabilityCatalog('工作流', '调度中心'),
      menuTree: menuTree('系统管理', '用户管理', '部门管理', '新建用户'),
    });
    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const zhDialog = await screen.findByRole('dialog');
    await capabilityGroupLabel(zhDialog, /^能\s*力$/);
    await within(zhDialog).findByText('工作流', {}, { timeout: 5000 });
    await within(zhDialog).findByText('调度中心', {}, { timeout: 5000 });
    const zhTexts = dialogTexts(zhDialog);
    expect(zhTexts.length).toBeGreaterThan(0);
    expect(zhTexts.filter((text) => BARE_KEY.test(text)),
      'zh 弹窗 chrome 不出现裸 key').toEqual([]);

    // —— en 腿：重新挂载页面（目录/菜单样例名换 en 数据——数据名非 i18n 文案），zh 文案消失 ——
    cleanup();
    await i18n.changeLanguage('en-US');
    await renderPage({
      catalog: capabilityCatalog('Workflow', 'Scheduler'),
      menuTree: menuTree('System', 'Users', 'Depts', 'Create user'),
    });
    fireEvent.click((await screen.findAllByText(/^New\s*package$/))[0]);
    const enDialog = await screen.findByRole('dialog');
    await capabilityGroupLabel(enDialog, /^capabilit\w*$/i);
    await within(enDialog).findByText('Workflow', {}, { timeout: 5000 });
    await within(enDialog).findByText('Scheduler', {}, { timeout: 5000 });

    const enTexts = dialogTexts(enDialog);
    expect(enTexts.length).toBeGreaterThan(0);
    expect(enTexts.filter((text) => CJK.test(text)),
      'en 弹窗按钮/占位/label 不残留中文').toEqual([]);
    const bare = enTexts.filter((text) => BARE_KEY.test(text));
    expect(bare, `发现裸 key：${bare.join(' | ')}`).toEqual([]);

    // —— 切回 zh：往返无状态残留（能力组 label 恢复中文） ——
    cleanup();
    await i18n.changeLanguage('zh-CN');
    await renderPage({
      catalog: capabilityCatalog('工作流', '调度中心'),
      menuTree: menuTree('系统管理', '用户管理', '部门管理', '新建用户'),
    });
    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const backDialog = await screen.findByRole('dialog');
    await capabilityGroupLabel(backDialog, /^能\s*力$/);
  });
});
