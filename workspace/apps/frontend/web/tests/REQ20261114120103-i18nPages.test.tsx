/**
 * REQ-20261114-120103 AC-1：i18n 全量迁移——代表页面文案随语言切换（zh-CN/en-US）、
 * locale 持久化（localStorage）、重挂载恢复、切回往返。
 *
 * 代表页面抽 3 个（委派单 AC-1 口径「抽 2-3 个代表页面」）：Tenants / Roles / Dicts。
 * 「无遗漏硬编码」由实现侧脚本扫描核验（契约 AC-1 括注）= TL 执行项，用例不穷举全站文案；
 * 本套件以「chrome 按钮面 CJK 清零 + 契约固定 zh 串消失 + 数据行保持」作为页面级切换断言。
 *
 * 装配照既有范本 web/tests/REQ20261114120101-crudPages.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 三段式权限播种 + <AntApp> 包裹 + testTimeout ≥15000ms。
 *
 * 【表面假定（编号 REQ20261114120103 套件内连续，汇总见交付含糊点清单）】
 *   A-1 core 经 '@acme/core' 导出 i18next 实例（契约变更清单「i18n 初始化（i18next 实例 + 资源包）」
 *       归 core）→ 测试以 changeLanguage+t 鸭子型在导出面定位，不猜测导出名；
 *       页面级渲染壳以该实例作 I18nextProvider（默认单例场景无感、Provider 注入场景兜底）。
 *   A-2 语言切换动因=对 core i18n 实例 changeLanguage（Header/Settings 入口殊途同归走同一 API）；
 *       入口控件自身装配不在本用例面（入口表面形态未契约化）。
 *   A-3 locale 持久化落在 localStorage（键名/编码格式未契约化）→ 断言=任一存储值含 'en-US'
 *       子串（兼容裸串与 JSON 内嵌）。
 *   A-4 重挂载恢复=同环境二次 render（模块单例保留、存储已持久化），非跨页签 reload。
 *   A-5 权限播种点对齐 crudPages 口径：租户无 DELETE 权限点（SystemPermissions）→ 租户页播种
 *       list/create/update；角色/字典播种全量 CRUD 点。
 *   A-6 en-US 文案具体译文未契约化 → 不对英文串做相等断言，以「zh 串消失 + zh 按钮 CJK 清零 +
 *       按钮面仍非空（结构未塌）」承载「文案随语言切换」。
 *
 * 【防复发口径落实】三段式权限播种；关闭态断言数据驱动；<AntApp> 包裹；testTimeout 15000。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act } from 'react';
import type { ComponentProps, ComponentType, ReactNode } from 'react';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { I18nextProvider } from 'react-i18next';
import { TenantsPage } from '../src/pages/TenantsPage';
import { RolesPage } from '../src/pages/RolesPage';
import { DictsPage } from '../src/pages/DictsPage';
import * as coreModule from '@acme/core';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

// React 18 act 环境（本文件直呼 act 驱动 changeLanguage 后的重渲染）
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// ————— 行为常量（crudPages 口径：两字按钮 autoInsertSpace 兼容） —————
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const CJK = /[\u4e00-\u9fff]/;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— 页面描述符（DTO 形状 = Admin*View wire 口径，照 crudPages fixture） —————
type PageSpec = {
  key: string;
  Component: ComponentType;
  pathPart: string;
  rows: Record<string, unknown>[];
  anchor: string;
  search: boolean;
  perms: string[];
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
    anchor: '爱思蜜科技',
    search: true,
    // 租户无 DELETE 权限点（SystemPermissions 仅 list/create/query/update，A-5）
    perms: ['system:tenant:list', 'system:tenant:create', 'system:tenant:update'],
  },
  {
    key: 'Roles',
    Component: RolesPage,
    pathPart: 'roles',
    rows: [
      { id: '3101', parentId: '0', roleCode: 'super_admin', roleName: '超级管理员', status: 'ENABLED', builtIn: true, remark: '', createdAt: '2026-01-01T00:00:00Z', children: [], version: '0' },
      { id: '3102', parentId: '0', roleCode: 'ops', roleName: '运维员', status: 'ENABLED', builtIn: false, remark: '', createdAt: '2026-01-02T00:00:00Z', children: [], version: '0' },
    ],
    anchor: '超级管理员',
    search: true,
    perms: ['system:role:list', 'system:role:create', 'system:role:update', 'system:role:delete'],
  },
  {
    key: 'Dicts',
    Component: DictsPage,
    // 弱匹配子串取 'dict'：类型域端点为 /admin/v1/system/dict-types*（crudPages A-34 口径）
    pathPart: 'dict',
    rows: [
      { id: '3401', dictCode: 'user_status', dictName: '用户状态', status: 'ENABLED', remark: '', createdAt: '2026-01-01T00:00:00Z', version: '0' },
      { id: '3402', dictCode: 'gender', dictName: '性别字典', status: 'ENABLED', remark: '', createdAt: '2026-01-02T00:00:00Z', version: '0' },
    ],
    anchor: '用户状态',
    search: false,
    perms: ['system:dict:list', 'system:dict:create', 'system:dict:update', 'system:dict:delete'],
  },
];

// ————— fetch 边界 mock —————
const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase() };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase() };
};

const setupPageApi = (spec: PageSpec) => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes(spec.pathPart)) return envelope(null);
    if (r.method === 'GET') {
      // 数字 id 子资源路径（字典条目 /dict-types/{id}/items 等）回空页（crudPages A-34 口径）
      const deep = new RegExp(`/${spec.pathPart}[^/]*\\/\\d`).test(r.url.pathname);
      return envelope({ records: deep ? [] : spec.rows, total: deep ? 0 : spec.rows.length });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
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

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const seedMe = async (permissions: string[]) => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser,
    roles: ['super_admin'],
    permissions,
    menus: [],
  })));
  await useMeStore.getState().load();
};

// ————— i18n 实例鸭子型定位（A-1：不猜导出名，changeLanguage+t 判定） —————
type I18nLike = {
  language?: string;
  changeLanguage: (lng: string) => Promise<unknown> | void;
  t: (key: string, opts?: unknown) => string;
};

const isI18nLike = (v: unknown): v is I18nLike =>
  !!v && typeof v === 'object'
  && typeof (v as I18nLike).changeLanguage === 'function'
  && typeof (v as I18nLike).t === 'function';

let cachedI18n: I18nLike | null = null;

const coreI18n = (): I18nLike => {
  if (cachedI18n) return cachedI18n;
  const mod = coreModule as unknown as Record<string, unknown>;
  for (const value of Object.values(mod)) {
    if (isI18nLike(value)) {
      cachedI18n = value;
      return value;
    }
  }
  throw new Error('A-1 未命中：@acme/core 导出面未发现 i18next 实例（changeLanguage+t 鸭子型判定）');
};

const switchLang = async (lng: 'zh-CN' | 'en-US') => {
  await act(async () => {
    await coreI18n().changeLanguage(lng);
  });
};

/**
 * 页面渲染壳（A-1 扩展）：以 core i18n 实例作 I18nextProvider——
 * 实现若用默认 i18next 单例则本壳无感（同实例）；若经 Provider 注入独立实例，
 * 独立渲染页面（无 App 装配）也拿到同一 core 实例。两场景断言面一致。
 */
type ProviderI18n = ComponentProps<typeof I18nextProvider>['i18n'];

const RenderShell = ({ children }: { children: ReactNode }) => (
  <I18nextProvider i18n={coreI18n() as unknown as ProviderI18n}>
    <AntApp>{children}</AntApp>
  </I18nextProvider>
);

// ————— chrome 断言工具 —————
const buttonLabels = () =>
  Array.from(document.querySelectorAll('button'))
    .map((b) => (b.textContent ?? '').replace(/\s+/g, ''))
    .filter(Boolean);

const cjkButtonLabels = () => buttonLabels().filter((l) => CJK.test(l));

const storageValues = () =>
  Array.from({ length: window.localStorage.length }, (_, i) =>
    window.localStorage.getItem(window.localStorage.key(i) ?? '') ?? '');

const rowByAnchor = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

beforeEach(async () => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
  // 基线钉死 zh-CN（jsdom navigator.language=en-US，防 LanguageDetector 抬初始语言，A-1/A-2）
  await switchLang('zh-CN');
  window.localStorage.clear();
});

afterEach(async () => {
  cleanup();
  await switchLang('zh-CN'); // 恢复全局缺省，防模块单例跨用例串扰
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— AC-1 主套件：代表页面文案随语言切换 —————
for (const spec of SPECS) {
  describe(`REQ20261114120103 i18n 页面文案切换（AC-1）——${spec.key}`, () => {
    it(`should_switch_page_chrome_when_locale_switched_to_en_us_on_${spec.key}`, async () => {
      await seedMe(spec.perms);
      setupPageApi(spec);
      render(<RenderShell><spec.Component /></RenderShell>);

      // zh-CN 基线：数据行渲染 + chrome 按钮面含中文（新建/编辑等）
      await screen.findByText(spec.anchor);
      expect(screen.getByText(ADD_BUTTON)).toBeTruthy();
      if (spec.search) {
        expect(screen.getByPlaceholderText(/搜\s*索/)).toBeTruthy();
      }
      const zhRow = await rowByAnchor(spec.anchor);
      expect(within(zhRow).getByText(EDIT_BUTTON)).toBeTruthy();
      const zhCjkLabels = cjkButtonLabels();
      expect(zhCjkLabels.length).toBeGreaterThan(0);

      // 切换 en-US（A-2）：zh chrome 消失、按钮面 CJK 清零、数据行保持（A-6）
      await switchLang('en-US');

      expect(screen.queryByText(ADD_BUTTON)).toBeNull();
      if (spec.search) {
        expect(screen.queryByPlaceholderText(/搜\s*索/)).toBeNull();
      }
      const enRow = await rowByAnchor(spec.anchor); // 重渲染后重取行节点（防陈旧引用）
      expect(within(enRow).queryByText(EDIT_BUTTON)).toBeNull();
      const enLabels = buttonLabels();
      expect(enLabels.length).toBeGreaterThan(0); // 按钮结构未塌（A-6）
      for (const label of zhCjkLabels) {
        expect(enLabels).not.toContain(label);
      }
      expect(cjkButtonLabels().length).toBe(0);
      expect(screen.queryByText(spec.anchor)).toBeTruthy(); // 数据不受语言切换影响
    });
  });
}

// ————— AC-1：持久化 + 重挂载恢复 + 往返切回 —————
describe('REQ20261114120103 i18n locale 持久化与重挂载（AC-1）', () => {
  it('should_persist_locale_and_restore_chrome_after_remount_when_en_us_selected', async () => {
    const spec = SPECS[0];
    await seedMe(spec.perms);
    setupPageApi(spec);
    render(<RenderShell><spec.Component /></RenderShell>);
    await screen.findByText(spec.anchor);

    await switchLang('en-US');

    // 持久化：localStorage 任一存储值含 en-US（A-3：键名/格式未契约化，值扫描兼容 JSON 内嵌）
    await waitFor(() => {
      expect(storageValues().some((v) => v.includes('en-US'))).toBe(true);
    });

    // 重挂载恢复（A-4）：卸载后重渲染仍为 en chrome
    cleanup();
    render(<RenderShell><spec.Component /></RenderShell>);
    await screen.findByText(spec.anchor);
    expect(screen.queryByText(ADD_BUTTON)).toBeNull();
    expect(cjkButtonLabels().length).toBe(0);

    // 往返：切回 zh-CN 后 chrome 恢复中文
    await switchLang('zh-CN');
    expect(screen.getByText(ADD_BUTTON)).toBeTruthy();
  });
});
