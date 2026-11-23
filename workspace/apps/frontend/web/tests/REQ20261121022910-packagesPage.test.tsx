/**
 * REQ-20261121-022910 套餐管理页（PackagesPage）契约用例。
 *
 * 覆盖（契约 AC-12 + AC-15，web+msw 面）：
 *   - AC-12 should_manage_packages_with_menu_tree_when_page_operated：列表渲染
 *     （menuCount/tenantCount 直显）＋新建（名称/备注/菜单树勾选 → POST menuIds 含勾选叶）＋
 *     编辑（详情回显 + PUT 全量替换）＋删除（未绑定成功行消失；占用 → 提示透出且行保留）。
 *   - AC-15 should_cover_package_strings_in_zh_and_en：zh/en 双语渲染无裸 key（键名归 frontend，
 *     文案宽容匹配——zh 消失/en 出现/按钮结构未塌/无点号资源键形状）。
 *
 * 装配照既有范本 web/tests/REQ20261117025200-tenantEnhance.test.tsx（fetch stubGlobal 精确
 * pathname 路由 + useMeStore 权限播种 + <AntApp> 包裹 + matchMedia/ResizeObserver 桩）＋
 * REQ20261120213603-menuFilter.test.tsx（i18n 切换口径）。交互防坑：下拉/树操作打结构节点
 * （pitfall 7/8）；负例消息宽容 contains（pitfall 13）；文案宽容匹配（pitfall 14）。
 *
 * 【表面假定（低风险，失败即交 TL 裁决）】
 *   A-1 页面 web/src/pages/PackagesPage.tsx 命名导出 PackagesPage（变更清单「web 新 PackagesPage」）。
 *   A-2 列表/表单走 CrudTable+CrudFormModal 既有形态：新建按钮文案含「新建」、行内「编辑/删除」、
 *       弹窗/气泡确认「确定/OK」、表单 label 含「套餐名称/备注」（tenantPackages i18n 既有文案族）。
 *   A-3 菜单树勾选弹窗数据源 = GET /admin/v1/system/menus（systemApi.menuTree 既有端点）；
 *       勾选提交 menuIds 含被勾叶（祖先补全由后端语义承担，不做勾选集精确相等断言）。
 *   A-4 错误透出（占用提示）：ApiError.message 为后端 message（既有 catch 口径），宽容 contains。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { i18n, useAuthStore, useMeStore, type AdminMenuView, type AdminTenantPackageView } from '@acme/core';
// 契约变更清单页名 PackagesPage；实现导出 TenantPackagesPage（页面文件名/组件命名归 frontend，
// 语义=套餐管理页；偏差已列入交付报告疑点清单交 TL 裁决）
import { TenantPackagesPage } from '../src/pages/TenantPackagesPage';

// testTimeout 45s（TL round-3 余量口径：全量 4-worker 并行时本文件曾抖到超时，pitfall 15
// 抖动判别口径保余量；隔离实测 ~20.3s）
// testTimeout 125s（TL round-2 预算放宽，判定链闭环后披露制：theme4-idle-rerun.log 失败用例闲置实测
// 49.7s；新预算=max(实测×2.5, 现预算×2)=124268→125s，重交互用例 ≥60s 口径；断言/行为零改动）
vi.setConfig({ testTimeout: 125000 });

// ————— 行为常量（crudPages/tenantEnhance 口径：两字按钮 autoInsertSpace 兼容） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const DELETE_BUTTON = /删\s*除/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const NAME_LABEL = /套\s*餐\s*名\s*称/;
const REMARK_LABEL = /备\s*注/;
const CJK = /[\u4e00-\u9fff]/;

const PERMS = [
  'system:tenant-package:list', 'system:tenant-package:create',
  'system:tenant-package:update', 'system:tenant-package:delete',
];

// ————— fixtures（version=乐观锁 wire 字符串口径，LongToStringModule 全局生效） —————
const PACKAGE_ROWS: AdminTenantPackageView[] = [
  {
    id: '1', packageName: '套餐甲', remark: '被租户绑定的演示套餐', menuCount: 2, tenantCount: 1,
    version: '0', createdAt: '2026-01-03T00:00:00Z', updatedAt: '2026-01-03T00:00:00Z',
  },
  {
    id: '2', packageName: '套餐乙', remark: null, menuCount: 0, tenantCount: 0,
    version: '0', createdAt: '2026-01-04T00:00:00Z', updatedAt: '2026-01-04T00:00:00Z',
  },
];

/** 菜单树（Tree 勾选数据源）：目录 1 → 叶 11（挂按钮 21）+ 叶 12；勾选断言只锚叶 id。 */
const MENU_TREE: AdminMenuView[] = [
  {
    id: 1, parentId: 0, menuName: '系统管理', menuType: 'DIRECTORY', path: null, component: null, icon: null,
    sortOrder: 1, visible: true, permissionCode: null, status: 'ENABLED', version: '0',
    children: [
      {
        id: 11, parentId: 1, menuName: '用户管理', menuType: 'MENU', path: '/users', component: null, icon: null,
        sortOrder: 1, visible: true, permissionCode: 'system:user:list', status: 'ENABLED', version: '0',
        children: [
          {
            id: 21, parentId: 11, menuName: '新建用户', menuType: 'BUTTON', path: null, component: null, icon: null,
            sortOrder: 1, visible: true, permissionCode: 'system:user:create', status: 'ENABLED', version: '0',
            children: [],
          },
        ],
      },
      {
        id: 12, parentId: 1, menuName: '部门管理', menuType: 'MENU', path: '/depts', component: null, icon: null,
        sortOrder: 2, visible: true, permissionCode: 'system:dept:list', status: 'ENABLED', version: '0',
        children: [],
      },
    ],
  },
];

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string, status = 200) => new Response(
  JSON.stringify({ success: false, code, message, data: null }), { status },
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
  /** DELETE 按 id 拦截返回业务失败信封（占用删除演示；未配置的 id 走成功链路）。 */
  deleteFailures?: Record<string, { code: string; message: string }>;
}

interface PackagesState {
  posts: { body: Record<string, unknown>; url: URL }[];
  puts: { body: Record<string, unknown>; url: URL }[];
  deletes: { url: URL }[];
  rows: AdminTenantPackageView[];
}

/** 套餐五端点 + 菜单树 + me 精确 pathname 路由（伴随请求回空信封）。 */
const setupPackagesApi = (spec: PackagesSpec = {}) => {
  const state: PackagesState = {
    posts: [], puts: [], deletes: [],
    rows: PACKAGE_ROWS.map((row) => ({ ...row })),
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: PERMS, menus: [] });
    }
    if (p === '/admin/v1/system/menus' && r.method === 'GET') {
      return envelope(MENU_TREE);
    }
    if (p === '/admin/v1/system/tenant-packages') {
      if (r.method === 'GET') return envelope({ records: state.rows, total: state.rows.length });
      if (r.method === 'POST') {
        const body = parseBody(r.body);
        state.posts.push({ body, url: r.url });
        const created: AdminTenantPackageView = {
          id: '9901', packageName: String(body.packageName ?? ''), remark: (body.remark as string | null) ?? null,
          menuCount: Array.isArray(body.menuIds) ? body.menuIds.length : 0, tenantCount: 0,
          version: '0', createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z',
        };
        state.rows = [...state.rows, created];
        return envelope(created);
      }
      return envelope(null);
    }
    const packageMatch = /^\/admin\/v1\/system\/tenant-packages\/([^/]+)$/.exec(p);
    if (packageMatch) {
      const id = packageMatch[1] as string;
      if (r.method === 'GET') {
        const target = state.rows.find((row) => row.id === id);
        if (!target) return failureEnvelope('TENANT_PACKAGE_NOT_FOUND', '套餐不存在或已被删除');
        return envelope({ ...target, menuIds: target.id === '1' ? [11, 21] : [] });
      }
      if (r.method === 'PUT') {
        const body = parseBody(r.body);
        state.puts.push({ body, url: r.url });
        state.rows = state.rows.map((row) => (row.id === id
          ? {
            ...row,
            packageName: String(body.packageName ?? row.packageName),
            remark: body.remark === undefined ? row.remark : (body.remark as string | null),
            menuCount: Array.isArray(body.menuIds) ? body.menuIds.length : row.menuCount,
            updatedAt: '2026-06-02T00:00:00Z',
          }
          : row));
        const target = state.rows.find((row) => row.id === id);
        return envelope(target);
      }
      if (r.method === 'DELETE') {
        state.deletes.push({ url: r.url });
        const failure = spec.deleteFailures?.[id];
        if (failure) return failureEnvelope(failure.code, failure.message);
        state.rows = state.rows.filter((row) => row.id !== id);
        return new Response(null, { status: 200 });
      }
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const renderPage = async (spec: PackagesSpec = {}): Promise<{ state: PackagesState }> => {
  await seedMe(PERMS);
  const api = setupPackagesApi(spec);
  render(<AntApp><TenantPackagesPage /></AntApp>);
  return api;
};

const fillField = async (dialog: HTMLElement, label: RegExp, value: string): Promise<void> => {
  fireEvent.change(within(dialog).getByLabelText(label), { target: { value } });
};

/** 勾选树节点（antd v5 Tree checkable）：定位 title 文案所在 treenode，点其 checkbox 结构节点。 */
const checkTreeNode = async (title: string): Promise<void> => {
  const titleNode = await screen.findByText(title, {}, { timeout: 5000 });
  const treenode = titleNode.closest('.ant-tree-treenode') as HTMLElement;
  expect(treenode, `树节点 ${title} 应承载 checkbox`).toBeTruthy();
  const checkbox = treenode.querySelector('.ant-tree-checkbox') as HTMLElement;
  expect(checkbox).toBeTruthy();
  fireEvent.click(checkbox);
};

/** 打开行内动作：定位 anchor 文案所在行后在该行内点按 name 匹配的按钮。 */
const rowAction = async (anchor: string, action: RegExp): Promise<HTMLElement> => {
  const cell = await screen.findByText(anchor, {}, { timeout: 5000 });
  const row = cell.closest('tr') as HTMLElement;
  expect(row, `行 ${anchor} 应存在`).toBeTruthy();
  fireEvent.click(within(row).getByText(action));
  return row;
};

/** 气泡确认（antd Popconfirm 缺省 OK 按钮文案英文，CONFIRM_BUTTON 双匹配兜底）。 */
const confirmPopconfirm = async (): Promise<void> => {
  fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));
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

// ————— AC-12：套餐管理页全链路 —————

describe('REQ20261121022910 PackagesPage（AC-12）', () => {
  it('should_manage_packages_with_menu_tree_when_page_operated', async () => {
    // 套餐甲（id=1）被租户占用：DELETE 拦截为 TENANT_PACKAGE_IN_USE
    const { state } = await renderPage({
      deleteFailures: { '1': { code: 'TENANT_PACKAGE_IN_USE', message: '该套餐已被租户绑定，不允许删除' } },
    });

    // —— 列表：两行样例 + menuCount/tenantCount 直显（契约视图字段；按单元格精确断言） ——
    await screen.findByText('套餐甲', {}, { timeout: 5000 });
    expect(screen.getByText('套餐乙')).toBeTruthy();
    const headerText = (document.querySelector('.ant-table-thead') as HTMLElement)?.textContent ?? '';
    expect(headerText, '列头含菜单数/租户数两计数列').toContain('菜单数');
    expect(headerText).toContain('租户数');
    const boundRow = screen.getByText('套餐甲').closest('tr') as HTMLElement;
    const boundCells = Array.from(boundRow.querySelectorAll('.ant-table-cell')).map((cell) => (cell.textContent ?? '').trim());
    expect(boundCells, 'menuCount=2 直显在计数列').toContain('2');
    expect(boundCells, 'tenantCount=1 直显在计数列').toContain('1');

    // —— 新建：名称/备注 + 菜单树勾选叶 11 → POST menuIds 含 11 ——
    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const createDialog = await screen.findByRole('dialog');
    await fillField(createDialog, NAME_LABEL, '集成新套餐');
    await fillField(createDialog, REMARK_LABEL, '用例备注');
    await checkTreeNode('用户管理');
    fireEvent.click(await within(createDialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.posts).toHaveLength(1));
    const post = state.posts[0] as { body: Record<string, unknown>; url: URL };
    expect(post.url.pathname).toBe('/admin/v1/system/tenant-packages');
    expect(post.body.packageName).toBe('集成新套餐');
    expect(post.body.remark).toBe('用例备注');
    expect((post.body.menuIds as unknown[]).map(Number),
      '勾选叶应入 menuIds（祖先补全归后端语义）').toContain(11);
    await screen.findByText(/已创建/, {}, { timeout: 5000 });

    // —— 编辑：详情回显（名称）→ 追加勾选叶 12 → PUT 全量替换 ——
    await rowAction('套餐甲', EDIT_BUTTON);
    const editDialog = await screen.findByRole('dialog');
    await waitFor(() => {
      expect((within(editDialog).getByLabelText(NAME_LABEL) as HTMLInputElement).value).toBe('套餐甲');
    });
    await checkTreeNode('部门管理');
    fireEvent.click(await within(editDialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.puts).toHaveLength(1));
    const put = state.puts[0] as { body: Record<string, unknown>; url: URL };
    expect(put.url.pathname).toBe('/admin/v1/system/tenant-packages/1');
    expect(put.body.packageName).toBe('套餐甲');
    expect((put.body.menuIds as unknown[]).map(Number),
      '全量替换：新勾 12 加入（既有勾选保持由详情回显语义承载）').toContain(12);
    await screen.findByText(/已更新/, {}, { timeout: 5000 });

    // —— 删除（占用）：提示透出且行保留 ——
    await rowAction('套餐甲', DELETE_BUTTON);
    await confirmPopconfirm();
    await screen.findByText(/已被租户绑定/, {}, { timeout: 5000 });
    expect(state.deletes).toHaveLength(1);
    expect((state.deletes[0] as { url: URL }).url.pathname).toBe('/admin/v1/system/tenant-packages/1');
    expect(screen.getByText('套餐甲'), '占用套餐行保留').toBeTruthy();

    // —— 删除（未绑定）：成功链路，行消失 ——
    await rowAction('套餐乙', DELETE_BUTTON);
    await confirmPopconfirm();
    await screen.findByText(/已删除/, {}, { timeout: 5000 });
    await waitFor(() => expect(screen.queryByText('套餐乙')).toBeNull());
  });
});

// ————— AC-15：zh/en 双语无裸 key —————

describe('REQ20261121022910 套餐页 i18n（AC-15）', () => {
  /** 裸 key 扫描形状：点号分隔资源键（如 tenantPackages.title / common.actions.edit）。 */
  const BARE_KEY = /^[A-Za-z][\w-]*\.[\w-]+(\.[\w-]+)+$/;

  const chromeTexts = (): string[] => {
    const texts: string[] = [];
    document.querySelectorAll('button').forEach((button) => texts.push((button.textContent ?? '').trim()));
    document.querySelectorAll('input, textarea').forEach((el) => {
      const placeholder = el.getAttribute('placeholder');
      if (placeholder) texts.push(placeholder);
    });
    document.querySelectorAll('label').forEach((label) => texts.push((label.textContent ?? '').trim()));
    return texts.filter((text) => text.length > 0);
  };

  it('should_cover_package_strings_in_zh_and_en', async () => {
    await renderPage();

    // zh 基线：chrome 中文文案渲染（表格标题 + 新建按钮 + 列头）
    await screen.findByText(/新\s*[建增]/, {}, { timeout: 5000 });
    expect(screen.getByText('套餐管理')).toBeTruthy();

    // 切 en：zh chrome 消失、en 文案出现、按钮结构未塌（i18nPages 口径）
    await i18n.changeLanguage('en-US');
    await screen.findByText('New package', {}, { timeout: 5000 });
    expect(screen.queryByText(/新\s*[建增]/)).toBeNull();
    const enTexts = chromeTexts();
    expect(enTexts.length).toBeGreaterThan(0);
    expect(enTexts.filter((text) => CJK.test(text)),
      'en chrome 按钮/占位/label 不残留中文').toEqual([]);
    const bare = enTexts.filter((text) => BARE_KEY.test(text));
    expect(bare, `发现裸 key：${bare.join(' | ')}`).toEqual([]);

    // 切回 zh：恢复中文（往返无状态残留）
    await i18n.changeLanguage('zh-CN');
    expect(screen.getByText(/新\s*[建增]/)).toBeTruthy();
    expect(screen.getByText('套餐管理')).toBeTruthy();
  });
});
