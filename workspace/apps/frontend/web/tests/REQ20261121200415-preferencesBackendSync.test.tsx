/**
 * REQ-20261121-200415（THEME-3）AC-7/AC-8：web 偏好后端持久化接线契约用例。
 *
 * 表面 = 契约确认记录⑥ + AC-7/AC-8：
 *   AC-7  登录成功后 GET /admin/v1/system/me/preferences → themePreference 非 null 则经
 *         migrateSettings 覆盖本地 preferences（后端权威）；themePreference=null 本地不动。
 *   AC-8  偏好变更即时本地生效 + 防抖 800ms 单次 PUT themePreference（按域 upsert）；
 *         401/未登录静默停上行，无未处理异常。
 *
 * 装配照 App 外壳范本 REQ20261116230354-appShell.test.tsx：render(<AntApp><HashRouter><App/></HashRouter></AntApp>)，
 *   fetch 桩信封 {success,code:'0',message:null,data}；/system/me/preferences 路由必须先于
 *   /system/me 通配判定（includes 串扰）；matchMedia/ResizeObserver/canvas 桩照抄（A-12/A-24 口径）。
 * 水合口径：web store 初始化后首个 dispatch 可能因水合被拒（实测 false），统一经
 *   changePrefs 辅助 waitFor 重试直到设置段落定，避免用例受初始化竞态影响。
 * 防抖计时用真实定时器：批量变更后同步断言零 PUT → 单次 PUT（3 变更→1 PUT 即防抖语义），
 *   避免 fake timers 与 waitFor/引导请求互锁（委派单「可用 fake timers」为可选口径）。
 * 「UI 即时生效」链路 = store.preferences → buildTheme/ThemeProvider，数据面由本套件锁定；
 *   渲染面已被既有外观抽屉用例（REQ20261121043502）覆盖，不在此重复断言 DOM。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useAuthStore, useMeStore, migrateSettings, DEFAULT_PREFERENCES, PREFERENCES_VERSION, type ThemePreferences } from '@acme/core';
import { useStore } from '../src/store/useStore';

vi.setConfig({ testTimeout: 20000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failEnvelope = (code: string, message: string, status = 200) => new Response(
  JSON.stringify({ success: false, code, message, data: null }), { status },
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

const mePayload = () => ({
  user: {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['super_admin'],
  permissions: ['system:user:list'],
  menus: [],
});

/** 与默认完全可区分的后端 blob 偏好（v3 全字段 + v4 四字段非默认，REQ-20261122-233427 适配）。 */
const backendPrefs = (over: Partial<ThemePreferences> = {}): ThemePreferences => ({
  mode: 'dark',
  accent: 'emerald',
  customAccent: '#b08968',
  density: 'compact',
  motionEnabled: false,
  radius: 'full',
  grayMode: true,
  weakMode: true,
  contentWidth: 'compact',
  semanticColors: { success: '#22c55e', warning: null, danger: '#c2410c' },
  layoutMode: 'top',
  tabStyle: 'card',
  siderTone: 'dark',
  topbarWidgets: {
    search: false, bell: false, lang: true, timezone: false, appearance: true,
    themeToggle: false, lock: true, fullscreen: false, settings: true,
  },
  ...over,
});

const backendBlob = (prefs: ThemePreferences) => ({ schemaVersion: 3, preferences: prefs });

/**
 * 外壳 API 桩。options：
 *   themePreference    GET data.themePreference（null=未设行）
 *   onPutPreferences   PUT /preferences 自定义应答（缺省成功信封）
 */
const setupSyncApi = (options: {
  themePreference?: Record<string, unknown> | null;
  onPutPreferences?: () => Response;
} = {}) => {
  const { themePreference = null, onPutPreferences } = options;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    // 注意：preferences 端点先于 /system/me 通配（includes 串扰）
    if (p === '/admin/v1/system/me/preferences' && r.method === 'GET') {
      return envelope({ messagePush: false, themePreference });
    }
    if (p === '/admin/v1/system/me/preferences' && r.method === 'PUT') {
      return onPutPreferences ? onPutPreferences() : envelope({ messagePush: false, themePreference: null });
    }
    if (p.includes('/system/me')) return envelope(mePayload());
    if (p.includes('/system/config')) return envelope({ watermarkEnabled: false, captchaEnabled: false, lockIdleTimeoutMs: 0, footerCopyright: null, footerVisible: false, watermarkTemplate: null, version: '0' });
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
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
  const fns = new Map<string | symbol, unknown>();
  const makeCtx = () => new Proxy({} as CanvasRenderingContext2D, {
    get(_t, prop) {
      if (prop === 'measureText') return () => ({ width: 24 });
      if (!fns.has(prop)) fns.set(prop, vi.fn());
      return fns.get(prop);
    },
    set() { return true; },
  });
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => makeCtx());
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockImplementation(
    () => 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==',
  );
};

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1', expiresAtMs: Date.now() + 1_800_000,
    userId: '1001', username: 'admin', nickname: null, tenantCode: 'default',
  }, false);
};

const settingsOf = (): { schemaVersion?: number; preferences?: Record<string, unknown> } =>
  useStore.getState().state.settings as unknown as { schemaVersion?: number; preferences?: Record<string, unknown> };

/** 偏好变更辅助：store 初始化首个 dispatch 可能未落定，重试至设置段生效（水合竞态兜底）。 */
const changePrefs = async (prefs: ThemePreferences): Promise<void> => {
  await waitFor(() => {
    useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: prefs } });
    expect(settingsOf().preferences).toEqual(prefs);
  });
};

const callsTo = (fetchMock: ReturnType<typeof vi.fn>, pathname: string, method: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => ({ ...asRequest(input, init), init }))
    .filter((r) => r.url.pathname === pathname && r.method === method);

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

const bootShell = async (fetchMock: ReturnType<typeof vi.fn>) => {
  // 登录态画像加载（appShell 范本口径：App 不自引 me.load，测试显式驱动）
  await useMeStore.getState().load();
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
  // 引导就绪锚：me 画像加载完成（登录态下 App 引导链路完成）
  await waitFor(() => expect(useMeStore.getState().loaded).toBe(true));
  return fetchMock;
};

beforeEach(() => {
  window.location.hash = '#/';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  window.location.hash = '';
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

describe('REQ20261121200415 后端权威覆盖本地（AC-7）', () => {
  it('should_override_local_preferences_with_backend_blob_when_theme_preference_present', async () => {
    const blob = backendBlob(backendPrefs());
    const fetchMock = setupSyncApi({ themePreference: blob });
    seedSession();
    // 先落一份与后端完全可区分的本地偏好，验证「后端权威覆盖」而非「本地保持」
    await changePrefs({ ...DEFAULT_PREFERENCES, mode: 'light', accent: 'cyan' });

    await bootShell(fetchMock);

    // 本地 preferences 被后端 blob 经 migrateSettings 覆盖（oracle 取契约钉死的迁移入口）
    const expected = migrateSettings(blob).settings.preferences;
    await waitFor(() => {
      expect(settingsOf().preferences).toEqual(expected);
    });
    expect(settingsOf().schemaVersion).toBe(PREFERENCES_VERSION);
  });

  it('should_keep_local_preferences_when_backend_theme_preference_null', async () => {
    const fetchMock = setupSyncApi({ themePreference: null });
    seedSession();
    const localPrefs = { ...DEFAULT_PREFERENCES, mode: 'dark' as const, accent: 'emerald' as const };
    await changePrefs(localPrefs);

    await bootShell(fetchMock);

    // 未设行（themePreference=null）：后端不内置主题默认值，本地偏好不被触碰（契约④⑥）
    await sleep(300);
    expect(settingsOf().preferences).toEqual(localPrefs);
  });
});

describe('REQ20261121200415 防抖上行（AC-8）', () => {
  it('should_put_theme_preference_once_after_debounce_when_preferences_changed_rapidly', async () => {
    const fetchMock = setupSyncApi({ themePreference: null });
    seedSession();
    await bootShell(fetchMock);
    expect(callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT')).toHaveLength(0);

    // 连续 3 次偏好变更：即时本地生效（同步），上行被防抖合并
    const bursts: ThemePreferences[] = [
      { ...DEFAULT_PREFERENCES, mode: 'dark', accent: 'emerald' },
      { ...DEFAULT_PREFERENCES, mode: 'dark', accent: 'violet' },
      { ...DEFAULT_PREFERENCES, mode: 'dark', accent: 'coral', customAccent: '#cd7a63' },
    ];
    for (const prefs of bursts) {
      await changePrefs(prefs);
      // 即时生效：本地 store 同步更新，先于网络上行
      expect(settingsOf().preferences).toEqual(prefs);
      expect(callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT')).toHaveLength(0);
    }

    // 防抖窗口后：单次 PUT，载荷为最后状态（jsdom 负载下放宽等待窗口，避免 1s 默认超时假红）
    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT')).toHaveLength(1);
    }, { timeout: 3000 });
    await sleep(1100); // 越过 800ms 窗口确认无追加 PUT
    const puts = callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT');
    expect(puts).toHaveLength(1);

    const body = parseBody(puts[0]?.body);
    const themePreference = body['themePreference'] as Record<string, unknown> | undefined;
    expect(themePreference, 'PUT 应上行 themePreference 域').toBeDefined();
    expect(themePreference?.['schemaVersion']).toBe(PREFERENCES_VERSION);
    expect(themePreference?.['preferences']).toEqual(bursts[2]);
    // 按域 upsert 口径：不上行 messagePush 实值（null=不动该域；缺省等价 null，红则 TL 裁决）
    expect(body['messagePush'] === undefined || body['messagePush'] === null).toBe(true);
  });

  it('should_not_put_preferences_when_not_logged_in', async () => {
    // 未登录：变更仅本地生效，静默停上行（零外呼、无未处理异常）
    const fetchMock = setupSyncApi({ themePreference: null });
    render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
    await sleep(200);

    const local = { ...DEFAULT_PREFERENCES, mode: 'dark' as const };
    await changePrefs(local);
    await sleep(1100);

    expect(callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT')).toHaveLength(0);
    expect(settingsOf().preferences).toEqual(local);
  });
});

describe('REQ20261121200415 401 静默（AC-8）', () => {
  it('should_silently_stop_uploading_without_rollback_when_put_returns_401', async () => {
    const fetchMock = setupSyncApi({
      themePreference: null,
      onPutPreferences: () => failEnvelope('UNAUTHORIZED', '未登录或会话过期', 401),
    });
    seedSession();
    await bootShell(fetchMock);

    const dispatched = { ...DEFAULT_PREFERENCES, mode: 'dark' as const, accent: 'ruby' as const };
    await changePrefs(dispatched);

    // 401 应答被消费（无未处理异常；本用例正常结束即锚），且单次尝试不重试风暴
    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT')).toHaveLength(1);
    }, { timeout: 3000 });
    await sleep(1100);
    expect(callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT')).toHaveLength(1);

    // 登出不回滚本地偏好（契约⑥）：本地保持已变更值
    expect(settingsOf().preferences).toEqual(dispatched);

    // 静默停上行：后续变更不再发起 PUT
    await changePrefs({ ...dispatched, accent: 'slate' as const });
    await sleep(1100);
    expect(callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT')).toHaveLength(1);
  });
});
