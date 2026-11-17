/**
 * REQ-20261117-025200：租户机制补强＋页脚版权（前端面）契约用例。
 *
 * 覆盖面（对齐契约 AC-10/AC-11/AC-13 前端子集 + REQ 委派单测试清单）：
 *   - 布局 Footer（web App.tsx Content 区）：footerVisible 且文案非空 → 渲染 .app-foot 文本；
 *     footerVisible=false 或 footerCopyright=null → 不渲染（空标签兜底）。
 *   - 登录页 Footer（LoginPage）：随所选租户 public-config 显隐；请求失败静默不显示（页脚非关键面）；
 *     文案空串不渲染；不影响既有验证码策略链路。
 *   - 超管租户切换（App.tsx 用户菜单）：仅 super_admin 可见入口（me.roles 判断）；
 *     成功链路 = POST /admin/v1/auth/tenant-switch（带 Authorization）→ 会话整体替换（userId wire string、
 *     remember 沿用）→ meStore 重新 load → 成功提示 → 回概览。
 *   - 非超管：入口不渲染、不请求租户目录。
 *   - TenantsPage：联系人/联系电话/有效期表单创建提交（expiresAt Dayjs→ISO）；编辑回显与有效期清空
 *     （PUT body expiresAt=null）。
 *   - SystemConfigPage：页脚版权卡读取回显 + 保存携带 footerCopyright/footerVisible（乐观锁口径不变）。
 *   - api 层：publicConfig 路径/查询参数/公开端点解包；tenantSwitch 路径/请求体/Authorization/解包。
 *
 * 装配照范本：fetch stubGlobal 精确 pathname 路由 + useMeStore 权限播种 + <AntApp> 包裹 +
 * matchMedia/ResizeObserver 桩 + testTimeout 放宽（REQ20261117011514 / REQ20261116230354-appShell 口径）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter, MemoryRouter } from 'react-router-dom';
import dayjs from 'dayjs';
import { App } from '../src/App';
import { LoginPage } from '../src/pages/LoginPage';
import { TenantsPage } from '../src/pages/TenantsPage';
import { SystemConfigPage } from '../src/pages/SystemConfigPage';
import { authApi, useAuthStore, useMeStore, useSystemConfigStore, type AdminSystemConfigView, type AdminTenantView } from '@acme/core';

vi.setConfig({ testTimeout: 20000 });

// ————— 行为常量 —————
const SAVE_BUTTON = /保\s*存/;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const ADD_BUTTON = /新\s*[建增]/;
const EDIT_BUTTON = /编\s*辑/;
const USER_MENU_TRIGGER = '打开用户菜单';
const SWITCH_SUBMENU = '切换租户';
const SWITCH_TARGET_LABEL = 'Beta 试点（beta）';
const SWITCH_SUCCESS_TEXT = '已切换至租户 beta';

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
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

const stubBrowserGaps = (matchMinWidth = false) => {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: matchMinWidth && query.includes('min-width'), media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
};

const resetStores = () => {
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({
    watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0,
    footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0', loaded: false,
  });
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  resetStores();
  // 基线桩：jsdom 缺 matchMedia/ResizeObserver（App 外壳/登录页/表格页均触达）
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  resetStores();
});

// ————— App 外壳域（布局 Footer + 租户切换入口） —————

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const seedSession = (tenantCode = 'default') => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1', expiresAtMs: Date.now() + 1_800_000,
    userId: '1001', username: 'admin', nickname: null, tenantCode,
  }, false);
};

interface ShellSpec {
  roles: string[];
  config: AdminSystemConfigView;
  tenants?: { tenantCode: string; tenantName: string }[];
  switchIssued?: Record<string, unknown>;
}

/** App 外壳有状态桩：me/config/tenants/tenant-switch 精确 pathname 路由（伴随请求回空信封）。 */
const setupShellApi = (spec: ShellSpec) => {
  const state = { meGets: 0, tenantGets: 0, switchPosts: [] as { body: Record<string, unknown>; authorization: string | null }[] };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me') {
      state.meGets += 1;
      return envelope({ user: meUser, roles: spec.roles, permissions: ['system:user:list', 'system:tenant:list', 'system:config:list'], menus: [] });
    }
    if (p === '/admin/v1/system/config') {
      return envelope({ ...spec.config });
    }
    if (p === '/admin/v1/auth/tenants') {
      state.tenantGets += 1;
      return envelope(spec.tenants ?? []);
    }
    if (p === '/admin/v1/auth/tenant-switch' && r.method === 'POST') {
      state.switchPosts.push({
        body: parseBody(r.body),
        authorization: new Headers(init?.headers).get('Authorization'),
      });
      return envelope(spec.switchIssued ?? {
        accessToken: 'switched-token', tokenType: 'Bearer',
        expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
        userId: '1001', username: 'admin', nickname: '超级管理员',
      });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const renderShell = async (spec: ShellSpec, startHash = '#/settings') => {
  stubBrowserGaps(true); // App 外壳按桌面布局渲染（断点 min-width 命中，照 appShell 范本）
  seedSession();
  const api = setupShellApi(spec);
  await useMeStore.getState().load();
  window.location.hash = startHash;
  const utils = render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  return { api, ...utils };
};

describe('REQ20261117025200 AC-10 布局 Footer（租户配置显隐）', () => {
  const baseConfig = (footerVisible: boolean, footerCopyright: string | null): AdminSystemConfigView => ({
    watermarkEnabled: false, captchaEnabled: false, lockIdleTimeoutMs: 0,
    footerCopyright, footerVisible, watermarkTemplate: null, version: '1',
  });

  it('should_renderFooterCopyrightText_when_footerVisibleAndCopyrightPresent', async () => {
    const { container } = await renderShell({ roles: ['super_admin'], config: baseConfig(true, '© 2026 ACME 云平台') }, '#/');

    // 引导完成锚：租户配置已加载为显示态
    await waitFor(() => {
      expect(useSystemConfigStore.getState().loaded).toBe(true);
      expect(useSystemConfigStore.getState().footerVisible).toBe(true);
    });
    const foot = await waitFor(() => {
      const el = container.querySelector('footer.app-foot');
      expect(el).toBeTruthy();
      return el as HTMLElement;
    });
    // 文本渲染 + 位于 Content 区普通文档流
    expect(foot.textContent).toBe('© 2026 ACME 云平台');
    const content = document.querySelector('main.ant-layout-content');
    expect(content?.contains(foot)).toBe(true);
  });

  it('should_notRenderFooter_when_footerVisibleFalse', async () => {
    const { container } = await renderShell({ roles: ['super_admin'], config: baseConfig(false, '© 2026 ACME 云平台') }, '#/');

    await waitFor(() => {
      expect(useSystemConfigStore.getState().loaded).toBe(true);
      expect(useSystemConfigStore.getState().footerVisible).toBe(false);
    });
    await screen.findByText('ACME 交付台'); // 外壳就绪锚
    expect(container.querySelector('footer.app-foot')).toBeNull();
  });

  it('should_notRenderEmptyFooter_when_copyrightNullEvenIfVisible', async () => {
    const { container } = await renderShell({ roles: ['super_admin'], config: baseConfig(true, null) }, '#/');

    await waitFor(() => {
      expect(useSystemConfigStore.getState().loaded).toBe(true);
      expect(useSystemConfigStore.getState().footerVisible).toBe(true);
      expect(useSystemConfigStore.getState().footerCopyright).toBeNull();
    });
    await screen.findByText('ACME 交付台'); // 外壳就绪锚
    expect(container.querySelector('footer.app-foot')).toBeNull();
  });
});

describe('REQ20261117025200 超管租户切换（App 用户菜单）', () => {
  const config: AdminSystemConfigView = {
    watermarkEnabled: false, captchaEnabled: false, lockIdleTimeoutMs: 0,
    footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0',
  };

  it('should_switchTenantAndReplaceSession_when_superAdminPicksTarget', async () => {
    const { api } = await renderShell({
      roles: ['super_admin'],
      config,
      tenants: [
        { tenantCode: 'acme', tenantName: 'ACME 总部' },
        { tenantCode: 'beta', tenantName: 'Beta 试点' },
      ],
    }, '#/settings');
    await screen.findByText('ACME 交付台'); // 外壳就绪锚

    // 打开用户菜单 → 拉取租户目录 → 展开子菜单
    fireEvent.mouseEnter(screen.getByRole('button', { name: USER_MENU_TRIGGER }));
    const switchTitle = await screen.findByText(SWITCH_SUBMENU, {}, { timeout: 5000 });
    fireEvent.mouseEnter(switchTitle);
    fireEvent.click(await screen.findByText(SWITCH_TARGET_LABEL, {}, { timeout: 5000 }));

    // 成功链路：tenant-switch POST（带 Authorization）→ 会话替换（tenantCode/令牌/userId wire string）
    await screen.findByText(SWITCH_SUCCESS_TEXT);
    expect(api.state.switchPosts).toHaveLength(1);
    expect(api.state.switchPosts[0]?.body).toEqual({ targetTenantCode: 'beta' });
    expect(api.state.switchPosts[0]?.authorization).toBe('Bearer token-1');
    await waitFor(() => {
      const session = useAuthStore.getState().session;
      expect(session?.tenantCode).toBe('beta');
      expect(session?.accessToken).toBe('switched-token');
      expect(session?.userId).toBe('1001');
      expect(typeof session?.userId).toBe('string');
    });
    // meStore 重新 load（切换前手动 load 1 次 + App 引导 1 次 → 切换后再 +1）
    await waitFor(() => expect(api.state.meGets).toBeGreaterThanOrEqual(3));
    // 回概览（go('/')）
    await waitFor(() => expect(window.location.hash).toBe('#/'));
    // remember 沿用当前值（播种 remember=false → 会话落 sessionStorage）
    expect(window.sessionStorage.getItem('acme.auth.session.v1')).toContain('switched-token');
    expect(window.localStorage.getItem('acme.auth.session.v1')).toBeNull();
  });

  it('should_hideSwitchEntry_when_userLacksSuperAdminRole', async () => {
    const { api } = await renderShell({ roles: ['ops'], config, tenants: [{ tenantCode: 'beta', tenantName: 'Beta 试点' }] }, '#/settings');
    await screen.findByText('ACME 交付台');

    fireEvent.mouseEnter(screen.getByRole('button', { name: USER_MENU_TRIGGER }));
    await screen.findByText('退出登录', {}, { timeout: 5000 });
    // 非超管：入口完全不渲染，且不请求租户目录
    expect(screen.queryByText(SWITCH_SUBMENU)).toBeNull();
    expect(api.state.tenantGets).toBe(0);
  });
});

// ————— 登录页 Footer（public-config 显隐 + 失败静默） —————

const TENANTS = [
  { tenantCode: 'acme', tenantName: 'ACME 总部' },
  { tenantCode: 'beta', tenantName: 'Beta 试点' },
];

const setupLoginApi = (options: {
  footers?: Record<string, { footerCopyright: string | null; footerVisible: boolean }>;
  publicConfigFail?: boolean;
} = {}) => {
  const state = { publicConfigGets: [] as string[], policyGets: [] as string[] };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/auth/tenants') return envelope(TENANTS);
    if (p === '/admin/v1/auth/captcha-policy') {
      state.policyGets.push(r.url.searchParams.get('tenantCode') ?? '');
      return envelope({ captchaEnabled: false });
    }
    if (p === '/admin/v1/auth/public-config') {
      const code = r.url.searchParams.get('tenantCode') ?? '';
      state.publicConfigGets.push(code);
      if (options.publicConfigFail) {
        return new Response(
          JSON.stringify({ success: false, code: 'AUTH_DOWNSTREAM', message: '配置服务不可用', data: null }),
          { status: 500 },
        );
      }
      return envelope(options.footers?.[code] ?? { footerCopyright: null, footerVisible: false });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const renderLogin = () => render(<AntApp><MemoryRouter><LoginPage /></MemoryRouter></AntApp>);

const flushAsync = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe('REQ20261117025200 AC-10 登录页 Footer（public-config）', () => {
  it('should_renderTenantCopyright_when_publicConfigVisibleAndCopyrightPresent', async () => {
    const { state } = setupLoginApi({
      footers: { acme: { footerCopyright: '© 2026 ACME 总部', footerVisible: true } },
    });
    const { container } = renderLogin();

    // 默认租户（acme）确定后即查询 public-config（独立于验证码策略）
    await waitFor(() => expect(state.publicConfigGets).toContain('acme'));
    await flushAsync();
    const foot = await waitFor(() => {
      const el = container.querySelector('footer.app-foot');
      expect(el).toBeTruthy();
      return el as HTMLElement;
    });
    expect(foot.textContent).toBe('© 2026 ACME 总部');
    // 既有验证码策略链路不受影响（同一租户同样发起策略查询）
    expect(state.policyGets).toContain('acme');
  });

  it('should_notRenderLoginFooter_when_publicConfigInvisibleOrCopyrightBlank', async () => {
    const { state } = setupLoginApi({
      footers: { acme: { footerCopyright: '© 2026 ACME 总部', footerVisible: false } },
    });
    const { container } = renderLogin();

    await waitFor(() => expect(state.publicConfigGets).toContain('acme'));
    await flushAsync();
    await screen.findByRole('combobox'); // 表单就绪锚（label 与 aria-label 双匹配，改用 role 锚）
    expect(container.querySelector('footer.app-foot')).toBeNull();

    // 文案空串：visible=true 也不渲染空标签
    fireEvent.mouseDown(screen.getByRole('combobox'));
    fireEvent.click(await screen.findByText('Beta 试点（beta）'));
    await waitFor(() => expect(state.publicConfigGets).toContain('beta'));
    await flushAsync();
    expect(container.querySelector('footer.app-foot')).toBeNull();
  });

  it('should_staySilentWithoutFooter_when_publicConfigRequestFails', async () => {
    const { state } = setupLoginApi({ publicConfigFail: true });
    const { container } = renderLogin();

    // 请求失败 → 静默（无页脚、无错误告警；页脚非关键面）
    await waitFor(() => expect(state.publicConfigGets).toContain('acme'));
    await flushAsync();
    await screen.findByLabelText('账号');
    expect(container.querySelector('footer.app-foot')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

// ————— TenantsPage（联系人/联系电话/有效期） —————

const TENANT_ROWS: AdminTenantView[] = [
  {
    id: '3001', tenantCode: 'acme', tenantName: '爱思蜜科技', status: 'ENABLED', remark: '主租户',
    contactName: '张三', contactPhone: '13800000000', expiresAt: '2026-03-01T00:00:00.000Z',
    createdAt: '2026-01-01T00:00:00Z', version: '0',
  },
  {
    id: '3002', tenantCode: 'beta', tenantName: '贝塔租户', status: 'ENABLED', remark: '',
    contactName: null, contactPhone: null, expiresAt: null,
    createdAt: '2026-01-02T00:00:00Z', version: '0',
  },
];

const setupTenantsApi = () => {
  const state = {
    rows: TENANT_ROWS.map((r) => ({ ...r })),
    posts: [] as Record<string, unknown>[],
    puts: [] as Record<string, unknown>[],
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (!p.includes('/system/tenants')) {
      if (p === '/admin/v1/system/me') {
        return envelope({ user: meUser, roles: ['super_admin'], permissions: ['system:tenant:list', 'system:tenant:create', 'system:tenant:update'], menus: [] });
      }
      return envelope(null);
    }
    if (r.method === 'GET') return envelope({ records: state.rows, total: state.rows.length });
    if (r.method === 'POST') {
      const body = parseBody(r.body);
      state.posts.push(body);
      const created = { id: '9901', status: 'ENABLED', createdAt: '2026-06-01T00:00:00Z', version: '0', ...body };
      state.rows = [created as AdminTenantView, ...state.rows];
      return envelope(created);
    }
    if (r.method === 'PUT') {
      const body = parseBody(r.body);
      state.puts.push(body);
      const id = p.split('/').filter(Boolean).pop() ?? '';
      const target = state.rows.find((item) => item.id === id) ?? null;
      if (!target) return envelope(null);
      const updated = { ...target, ...body };
      state.rows = state.rows.map((item) => (item.id === id ? updated : item));
      return envelope(updated);
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const seedMeTenants = async () => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser, roles: ['super_admin'],
    permissions: ['system:tenant:list', 'system:tenant:create', 'system:tenant:update'], menus: [],
  })));
  await useMeStore.getState().load();
};

const fillField = async (label: string, value: string, dialog?: HTMLElement) => {
  fireEvent.change(within(dialog ?? document.body).getByLabelText(label), { target: { value } });
};

/** DatePicker 输入提交（照 antd 官方 jsdom 口径：focus → change → Enter 提交解析值）。 */
const fillExpiryDate = async (dialog: HTMLElement, value: string) => {
  const input = within(dialog).getByLabelText('有效期') as HTMLInputElement;
  fireEvent.focus(input);
  fireEvent.change(input, { target: { value } });
  fireEvent.keyDown(input, { key: 'Enter', keyCode: 13, which: 13 });
  return input;
};

describe('REQ20261117025200 AC-11 租户管理表单（联系人/联系电话/有效期）', () => {
  it('should_submitContactFieldsAndIsoExpiry_when_createTenant', async () => {
    await seedMeTenants();
    const { state } = setupTenantsApi();
    render(<AntApp><TenantsPage /></AntApp>);

    fireEvent.click((await screen.findAllByText(ADD_BUTTON))[0]);
    const dialog = await screen.findByRole('dialog');
    await fillField('租户代码', 'xin-zu-hu-1', dialog);
    await fillField('租户名称', '新租户甲', dialog);
    await fillField('联系人', '李四', dialog);
    await fillField('联系电话', '13900000001', dialog);
    await fillExpiryDate(dialog, '2026-12-31');
    fireEvent.click(await within(dialog).findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => expect(state.posts).toHaveLength(1));
    const body = state.posts[0] ?? {};
    expect(body.tenantCode).toBe('xin-zu-hu-1');
    expect(body.contactName).toBe('李四');
    expect(body.contactPhone).toBe('13900000001');
    // Dayjs → ISO-8601 字符串（所选日期语义一致）
    expect(typeof body.expiresAt).toBe('string');
    expect(dayjs(String(body.expiresAt)).format('YYYY-MM-DD')).toBe('2026-12-31');
  });

  it('should_echoContactFieldsAndClearExpiryToNull_when_editTenant', async () => {
    await seedMeTenants();
    const { state } = setupTenantsApi();
    render(<AntApp><TenantsPage /></AntApp>);

    const row = await screen.findByText('爱思蜜科技');
    fireEvent.click(within(row.closest('tr') as HTMLElement).getByText(EDIT_BUTTON));
    const dialog = await screen.findByRole('dialog');

    // 编辑回显：联系人与有效期（dayjs echo 由控件承载，此处验证提交语义）
    await waitFor(() => {
      expect((within(dialog).getByLabelText('联系人') as HTMLInputElement).value).toBe('张三');
      expect((within(dialog).getByLabelText('联系电话') as HTMLInputElement).value).toBe('13800000000');
      expect((within(dialog).getByLabelText('有效期') as HTMLInputElement).value).not.toBe('');
    });

    // 清空有效期（allowClear）：hover 显示清除按钮 → 点击
    const expiryInput = within(dialog).getByLabelText('有效期') as HTMLInputElement;
    const pickerRoot = expiryInput.closest('.ant-picker') as HTMLElement;
    fireEvent.mouseEnter(pickerRoot);
    const clearIcon = await waitFor(() => {
      const el = pickerRoot.querySelector('.ant-picker-clear');
      expect(el).toBeTruthy();
      return el as HTMLElement;
    });
    fireEvent.click(clearIcon);
    await waitFor(() => expect(expiryInput.value).toBe(''));

    fireEvent.click(await within(dialog).findByRole('button', { name: CONFIRM_BUTTON }));

    // PUT body：有效期清空 → null（长期）；联系字段随体透传；乐观锁 version 沿用
    await waitFor(() => expect(state.puts).toHaveLength(1));
    const body = state.puts[0] ?? {};
    expect(body.tenantName).toBe('爱思蜜科技');
    expect(body.contactName).toBe('张三');
    expect(body.contactPhone).toBe('13800000000');
    expect(body.expiresAt).toBeNull();
    expect(body.version).toBe('0');
  });
});

// ————— SystemConfigPage（页脚版权卡） —————

const failureEnvelope = (message: string) => new Response(
  JSON.stringify({ success: false, code: 'platform-system-08-0003', message, data: null }), { status: 200 },
);

const setupConfigApi = (initial: AdminSystemConfigView) => {
  const state = { ...initial };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    if (!r.url.pathname.includes('/system/config')) return envelope(null);
    if (r.method === 'GET') return envelope({ ...state });
    if (r.method === 'PUT') {
      const body = parseBody(r.body);
      if (String(body.version) !== state.version) {
        return failureEnvelope('数据已被他人修改，请刷新后重试');
      }
      state.watermarkEnabled = Boolean(body.watermarkEnabled);
      state.captchaEnabled = Boolean(body.captchaEnabled);
      state.lockIdleTimeoutMs = Number(body.lockIdleTimeoutMs);
      state.footerCopyright = (body.footerCopyright as string | null) ?? null;
      state.footerVisible = body.footerVisible === true;
      state.watermarkTemplate = (body.watermarkTemplate as string | null) ?? null;
      state.version = String(Number(state.version) + 1);
      return envelope({ ...state });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const seedMeConfig = async () => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: meUser, roles: ['super_admin'],
    permissions: ['system:config:list', 'system:config:update'], menus: [],
  })));
  await useMeStore.getState().load();
};

describe('REQ20261117025200 系统设置页脚版权卡', () => {
  it('should_echoAndSubmitFooterFields_when_adminEditsFooterAndSaves', async () => {
    await seedMeConfig();
    const { fetchMock } = setupConfigApi({
      watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0,
      footerCopyright: '© 2026 旧文案', footerVisible: false, watermarkTemplate: null, version: '0',
    });
    render(<AntApp><SystemConfigPage /></AntApp>);

    // 读取回显：文案入 TextArea、开关回显关闭态
    const textarea = await screen.findByLabelText('页脚版权文案') as HTMLTextAreaElement;
    await waitFor(() => expect(textarea.value).toBe('© 2026 旧文案'));
    const visibleSwitch = screen.getByLabelText('显示页脚版权');
    await waitFor(() => expect(visibleSwitch.getAttribute('aria-checked')).toBe('false'));

    // 编辑：改文案 + 打开显隐开关 → 保存
    fireEvent.change(textarea, { target: { value: '© 2026 新文案' } });
    fireEvent.click(visibleSwitch);
    await waitFor(() => expect(visibleSwitch.getAttribute('aria-checked')).toBe('true'));
    fireEvent.click(screen.getByRole('button', { name: SAVE_BUTTON }));

    await screen.findByText(/成功/);
    await waitFor(() => {
      const puts = (fetchMock.mock.calls as unknown as FetchArgs[])
        .map(([input, init]) => asRequest(input, init))
        .filter((r) => r.url.pathname.includes('/system/config') && r.method === 'PUT');
      expect(puts).toHaveLength(1);
      expect(parseBody(puts[0]?.body)).toEqual({
        watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0,
        footerCopyright: '© 2026 新文案', footerVisible: true, watermarkTemplate: null, version: '0',
      });
    });
    // 保存成功后 store 回写（页脚即时联动）
    expect(useSystemConfigStore.getState().footerCopyright).toBe('© 2026 新文案');
    expect(useSystemConfigStore.getState().footerVisible).toBe(true);
  });

  it('should_submitNullCopyright_when_textareaClearedToBlank', async () => {
    await seedMeConfig();
    const { fetchMock } = setupConfigApi({
      watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0,
      footerCopyright: '© 2026 旧文案', footerVisible: true, watermarkTemplate: null, version: '0',
    });
    render(<AntApp><SystemConfigPage /></AntApp>);

    const textarea = await screen.findByLabelText('页脚版权文案') as HTMLTextAreaElement;
    await waitFor(() => expect(textarea.value).toBe('© 2026 旧文案'));
    fireEvent.change(textarea, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: SAVE_BUTTON }));

    await screen.findByText(/成功/);
    await waitFor(() => {
      const puts = (fetchMock.mock.calls as unknown as FetchArgs[])
        .map(([input, init]) => asRequest(input, init))
        .filter((r) => r.url.pathname.includes('/system/config') && r.method === 'PUT');
      expect(puts).toHaveLength(1);
      // 空文案归一 null（契约：null/空串均不渲染页脚）
      expect(parseBody(puts[0]?.body)).toMatchObject({ footerCopyright: null, footerVisible: true });
    });
  });
});

// ————— api 层（publicConfig / tenantSwitch 路径与解包） —————

describe('REQ20261117025200 api 层 publicConfig/tenantSwitch', () => {
  it('should_requestPublicConfigPathAndUnwrap_when_publicConfigCalled', async () => {
    const captured: { current: { url: URL; init?: RequestInit } | null } = { current: null };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      captured.current = { url: new URL(String(input), 'http://localhost'), init };
      return envelope({ footerCopyright: '© X', footerVisible: true });
    }));

    const view = await authApi.publicConfig('acme');

    // 信封解包 + 路径/查询参数 + 公开端点（不带 Authorization）
    expect(view).toEqual({ footerCopyright: '© X', footerVisible: true });
    expect(captured.current?.url.pathname).toBe('/admin/v1/auth/public-config');
    expect(captured.current?.url.searchParams.get('tenantCode')).toBe('acme');
    expect(new Headers(captured.current?.init?.headers).get('Authorization')).toBeNull();
  });

  it('should_postTargetTenantCodeWithAuthorization_when_tenantSwitchCalled', async () => {
    seedSession('default');
    const captured: { current: { url: URL; init?: RequestInit } | null } = { current: null };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      captured.current = { url: new URL(String(input), 'http://localhost'), init };
      return envelope({
        accessToken: 'switched-token', tokenType: 'Bearer',
        expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
        userId: '1001', username: 'admin', nickname: '超级管理员',
      });
    }));

    const issued = await authApi.tenantSwitch('beta');

    // 认证端点：POST /admin/v1/auth/tenant-switch，body {targetTenantCode}，带 Authorization
    expect(captured.current?.url.pathname).toBe('/admin/v1/auth/tenant-switch');
    expect(captured.current?.init?.method?.toUpperCase()).toBe('POST');
    expect(parseBody(captured.current?.init?.body)).toEqual({ targetTenantCode: 'beta' });
    expect(new Headers(captured.current?.init?.headers).get('Authorization')).toBe('Bearer token-1');
    // 信封解包为 AdminTokenIssued（userId wire string 口径）
    expect(issued.userId).toBe('1001');
    expect(typeof issued.userId).toBe('string');
    expect(issued.accessToken).toBe('switched-token');
  });
});
