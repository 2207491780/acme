/**
 * REQ-20261117-011514：租户级验证码开关（前端面）契约用例。
 *
 * AC-7（登录页）：按所选租户策略（GET /admin/v1/auth/captcha-policy?tenantCode=）显隐验证码——
 *   策略关：不渲染验证码输入框、不请求图形验证码接口（GET /admin/v1/auth/captcha）、
 *   提交体不含 captchaId/captchaCode；策略开：渲染并照常提交（既有回归）；
 *   策略请求失败（网络/5xx）→ fail-closed 渲染并照常提交；切换租户重新取策略。
 * AC-8（系统设置页）：「登录验证码」开关切换保存提交 captchaEnabled；读取回显；
 *   无 system:config:update 时开关禁用（与保存按钮 fail-closed 一致）。
 * AC-9（api/mock 面）：captchaPolicy 请求路径/查询参数与信封解包正确性（公开端点不带 Authorization）。
 *
 * 装配照范本 web/tests/REQ20261116230354-systemConfigPage.test.tsx：fetch stubGlobal +
 * useMeStore 权限播种 + <AntApp> 包裹 + matchMedia/ResizeObserver 桩 + testTimeout 放宽；
 * LoginPage 另需 Router 上下文（useNavigate）→ MemoryRouter。
 * fetch 桩路由用【精确 pathname】匹配：/admin/v1/auth/captcha 与 /admin/v1/auth/captcha-policy
 * 前缀相交，includes 弱匹配会互相误命中。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { LoginPage } from '../src/pages/LoginPage';
import { SystemConfigPage } from '../src/pages/SystemConfigPage';
import { authApi, useAuthStore, useMeStore, useSystemConfigStore, type AdminSystemConfigView } from '@acme/core';

vi.setConfig({ testTimeout: 20000 });

// ————— 行为常量 —————
const CAPTCHA_INPUT = '图形验证码';
const CAPTCHA_LABEL = /登录验证码/;
const SAVE_BUTTON = /保\s*存/;
const LOGIN_BUTTON = /登\s*录/;

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

// ————— 登录域有状态桩（tenants / captcha-policy / captcha / tokens，精确 pathname 路由） —————

const TENANTS = [
  { tenantCode: 'acme', tenantName: 'ACME 总部' },
  { tenantCode: 'beta', tenantName: 'Beta 试点' },
];

const setupLoginApi = (options: { policies?: Record<string, boolean>; policyFail?: boolean } = {}) => {
  const state = {
    policyGets: [] as string[],
    captchaGets: 0,
    loginBodies: [] as Record<string, unknown>[],
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/auth/tenants') return envelope(TENANTS);
    if (p === '/admin/v1/auth/captcha-policy') {
      const code = r.url.searchParams.get('tenantCode') ?? '';
      state.policyGets.push(code);
      if (options.policyFail) {
        return new Response(
          JSON.stringify({ success: false, code: 'AUTH_DOWNSTREAM', message: '策略服务不可用', data: null }),
          { status: 500 },
        );
      }
      return envelope({ captchaEnabled: options.policies?.[code] ?? false });
    }
    if (p === '/admin/v1/auth/captcha') {
      state.captchaGets += 1;
      return envelope({ captchaId: `captcha-${state.captchaGets}`, imageBase64Data: 'data:image/png;base64,iVBORw0KGgo=' });
    }
    if (p === '/admin/v1/auth/tokens' && r.method === 'POST') {
      const body = parseBody(r.body);
      state.loginBodies.push(body);
      return envelope({
        accessToken: 'mock-token', tokenType: 'Bearer',
        expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
        userId: '1001', username: String(body.username ?? 'admin'), nickname: '超级管理员',
      });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

const renderLogin = () =>
  render(<AntApp><MemoryRouter><LoginPage /></MemoryRouter></AntApp>);

const fillField = async (label: string, value: string) => {
  fireEvent.change(await screen.findByLabelText(label), { target: { value } });
};

/** 冲刷在途微任务链（策略 promise → setState → React 重渲染），保证后续断言/提交在策略落定后。 */
const flushAsync = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

// ————— 系统配置域有状态桩（照范本 REQ20261116230354-systemConfigPage.test.tsx 口径） —————

const failureEnvelope = (message: string) => new Response(
  JSON.stringify({ success: false, code: 'platform-system-08-0003', message, data: null }), { status: 200 },
);

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes('/system/config') && r.method === method);

/** 有状态 config 桩：PUT 成功更新 state（version 不匹配→业务失败），供保存后回读断言。 */
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
      state.version = String(Number(state.version) + 1);
      return envelope({ ...state });
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
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

const resetStores = () => {
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useSystemConfigStore.setState({ watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0, version: '0', loaded: false });
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  resetStores();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  resetStores();
});

// ————— AC-7 登录页验证码策略显隐 —————

describe('REQ20261117011514 AC-7 登录页验证码策略', () => {
  it('should_notRenderCaptchaAndOmitCaptchaFields_when_policyDisabled', async () => {
    const { state } = setupLoginApi({ policies: { acme: false, beta: true } });
    renderLogin();

    // 默认租户（acme）确定后即查询策略，并等策略落定（关）
    await waitFor(() => expect(state.policyGets).toContain('acme'));
    await flushAsync();

    // 策略关稳态：不渲染验证码输入框；全程不请求图形验证码接口
    await waitFor(() => {
      expect(screen.queryByLabelText(CAPTCHA_INPUT)).toBeNull();
      expect(state.captchaGets).toBe(0);
    });

    await fillField('账号', 'alice');
    await fillField('密码', 'pw-123456');
    fireEvent.click(screen.getByRole('button', { name: LOGIN_BUTTON }));

    // 提交体不含 captchaId/captchaCode（契约确认记录③）
    await waitFor(() => expect(state.loginBodies).toHaveLength(1));
    expect(state.loginBodies[0]).toEqual({ tenantCode: 'acme', username: 'alice', password: 'pw-123456' });
    expect(state.captchaGets).toBe(0);
  });

  it('should_renderCaptchaAndSubmitCaptchaFields_when_policyEnabled', async () => {
    const { state } = setupLoginApi({ policies: { acme: true, beta: false } });
    renderLogin();

    // 策略开：渲染输入框并请求图形验证码（既有回归）
    await screen.findByLabelText(CAPTCHA_INPUT);
    await waitFor(() => expect(state.captchaGets).toBeGreaterThanOrEqual(1));

    await fillField('账号', 'alice');
    await fillField('密码', 'pw-123456');
    fireEvent.change(screen.getByLabelText(CAPTCHA_INPUT), { target: { value: 'abcd' } });
    fireEvent.click(screen.getByRole('button', { name: LOGIN_BUTTON }));

    // 提交体携带 captchaId/captchaCode（既有契约回归）
    await waitFor(() => expect(state.loginBodies).toHaveLength(1));
    expect(state.loginBodies[0]).toEqual({
      tenantCode: 'acme', username: 'alice', password: 'pw-123456',
      captchaId: 'captcha-1', captchaCode: 'abcd',
    });
  });

  it('should_renderCaptchaFailClosed_when_policyRequestFails', async () => {
    const { state } = setupLoginApi({ policyFail: true });
    renderLogin();

    await waitFor(() => expect(state.policyGets).toContain('acme'));
    await flushAsync();

    // fail-closed：策略查询失败按「开启」处理 → 渲染输入框并请求图形验证码
    await screen.findByLabelText(CAPTCHA_INPUT);
    await waitFor(() => expect(state.captchaGets).toBeGreaterThanOrEqual(1));

    await fillField('账号', 'alice');
    await fillField('密码', 'pw-123456');
    fireEvent.change(screen.getByLabelText(CAPTCHA_INPUT), { target: { value: 'abcd' } });
    fireEvent.click(screen.getByRole('button', { name: LOGIN_BUTTON }));

    await waitFor(() => expect(state.loginBodies).toHaveLength(1));
    expect(state.loginBodies[0]).toEqual({
      tenantCode: 'acme', username: 'alice', password: 'pw-123456',
      captchaId: 'captcha-1', captchaCode: 'abcd',
    });
  });

  it('should_refetchPolicyAndRerenderCaptcha_when_tenantSwitched', async () => {
    const { state } = setupLoginApi({ policies: { acme: false, beta: true } });
    renderLogin();

    await waitFor(() => expect(state.policyGets).toContain('acme'));
    await flushAsync();
    await waitFor(() => {
      expect(screen.queryByLabelText(CAPTCHA_INPUT)).toBeNull();
      expect(state.captchaGets).toBe(0);
    });

    // 切换租户 acme → beta：重新取策略，验证码面随策略开而出现
    fireEvent.mouseDown(screen.getByRole('combobox'));
    fireEvent.click(await screen.findByText('Beta 试点（beta）'));

    await waitFor(() => expect(state.policyGets).toContain('beta'));
    await flushAsync();
    await screen.findByLabelText(CAPTCHA_INPUT);
    await waitFor(() => expect(state.captchaGets).toBeGreaterThanOrEqual(1));

    await fillField('账号', 'alice');
    await fillField('密码', 'pw-123456');
    fireEvent.change(screen.getByLabelText(CAPTCHA_INPUT), { target: { value: 'abcd' } });
    fireEvent.click(screen.getByRole('button', { name: LOGIN_BUTTON }));

    await waitFor(() => expect(state.loginBodies).toHaveLength(1));
    expect(state.loginBodies[0]).toEqual({
      tenantCode: 'beta', username: 'alice', password: 'pw-123456',
      captchaId: 'captcha-1', captchaCode: 'abcd',
    });
  });
});

// ————— AC-8 系统设置页登录验证码开关 —————

describe('REQ20261117011514 AC-8 系统设置页验证码开关', () => {
  it('should_submitCaptchaEnabled_when_adminTogglesCaptchaAndSaves', async () => {
    await seedMe(['system:config:list', 'system:config:update']);
    const { fetchMock, state } = setupConfigApi({ watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0, footerCopyright: null, footerVisible: false, version: '0' });
    render(<AntApp><SystemConfigPage /></AntApp>);

    // 读取回显（缺省关）→ 切换为开 → 保存
    const captchaSwitch = await screen.findByLabelText(CAPTCHA_LABEL);
    await waitFor(() => expect(captchaSwitch.getAttribute('aria-checked')).toBe('false'));
    fireEvent.click(captchaSwitch);
    await waitFor(() => expect(captchaSwitch.getAttribute('aria-checked')).toBe('true'));
    fireEvent.click(screen.getByRole('button', { name: SAVE_BUTTON }));

    await screen.findByText(/成功/);
    await waitFor(() => {
      const puts = callsOf(fetchMock, 'PUT');
      expect(puts).toHaveLength(1);
      expect(parseBody(puts[0]?.body)).toEqual({
        watermarkEnabled: true, captchaEnabled: true, lockIdleTimeoutMs: 0,
        footerCopyright: null, footerVisible: false, version: '0',
      });
    });
    // 保存成功后 store 回写（重新读取一致）
    expect(useSystemConfigStore.getState().captchaEnabled).toBe(true);
    expect(state.captchaEnabled).toBe(true);
  });

  it('should_echoCaptchaEnabledFromConfig_when_pageLoads', async () => {
    await seedMe(['system:config:list', 'system:config:update']);
    setupConfigApi({ watermarkEnabled: true, captchaEnabled: true, lockIdleTimeoutMs: 0, footerCopyright: null, footerVisible: false, version: '2' });
    render(<AntApp><SystemConfigPage /></AntApp>);

    // 服务端 captchaEnabled=true → 开关回显开启
    const captchaSwitch = await screen.findByLabelText(CAPTCHA_LABEL);
    await waitFor(() => expect(captchaSwitch.getAttribute('aria-checked')).toBe('true'));
  });

  it('should_disableCaptchaSwitchAndHideSave_when_noUpdatePermission', async () => {
    await seedMe(['system:config:list']);
    setupConfigApi({ watermarkEnabled: true, captchaEnabled: false, lockIdleTimeoutMs: 0, footerCopyright: null, footerVisible: false, version: '0' });
    render(<AntApp><SystemConfigPage /></AntApp>);

    const captchaSwitch = await screen.findByLabelText(CAPTCHA_LABEL);
    // 无 system:config:update → 表单只读（开关禁用），保存入口不渲染（与 watermark fail-closed 同口径）
    await waitFor(() => expect(captchaSwitch.hasAttribute('disabled')).toBe(true));
    expect(screen.queryByRole('button', { name: SAVE_BUTTON })).toBeNull();
  });
});

// ————— AC-9 captchaPolicy api/mock 面（路径 + 信封解包 + 公开端点口径） —————

describe('REQ20261117011514 captchaPolicy api 层', () => {
  it('should_requestPolicyEndpointWithTenantCodeAndUnwrapEnvelope_when_captchaPolicyCalled', async () => {
    // ref 容器承载闭包捕获（let 直捕会被 TS 控制流收窄为 null）
    const captured: { current: { url: URL; init?: RequestInit } | null } = { current: null };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      captured.current = { url: new URL(String(input), 'http://localhost'), init };
      return envelope({ captchaEnabled: true });
    });
    vi.stubGlobal('fetch', fetchMock);

    const policy = await authApi.captchaPolicy('default');

    // 信封解包：Result<CaptchaPolicyView> → { captchaEnabled }
    expect(policy).toEqual({ captchaEnabled: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(captured.current?.url.pathname).toBe('/admin/v1/auth/captcha-policy');
    expect(captured.current?.url.searchParams.get('tenantCode')).toBe('default');
    // 公开端点：不携带 Authorization（对齐同文件 tenants() 口径）
    expect(new Headers(captured.current?.init?.headers).get('Authorization')).toBeNull();
  });
});
