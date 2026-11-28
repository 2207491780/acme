/** @vitest-environment jsdom */
/**
 * REQ-20261127-234158（wire 契约·DTO 字段对齐 P1-06）C 端会话过期毫秒行为级用例（AC-1/AC-2）。
 *
 * 【行为级，非对照】与 frontend-dev 已交付的 mobile/tests/REQ20261127234158-authWireContract.test.ts
 * （非 MSW 纯数据 wire 对照，AC-9 FE fixture 侧锚）互补：本文件从「登录/刷新链路」视角断言
 * 真实 wire 形态被消费为会话毫秒——mock fetch 返回钉死 wire 形态的 AppTokenIssued，走
 * LoginScreen 登录与 client.ts 静默刷新，落库断言（MSW 无涉，fetch 桩直注）。
 *
 * 钉死 wire golden（事实源=backend REQ20261127234158*WireTest.java 的 AppTokenIssued 序列化用例；
 * 键集与 mobile/tests/REQ20261127234158-authWireContract.test.ts 的 TOKEN_WIRE 逐键一致）：
 *   - 键：accessToken/refreshToken/accessExpiresAt/refreshExpiresAt/userId/phone；
 *   - 两个过期时间为 ISO-8601 UTC 字符串；C 端 wire 不存在 `expiresAt` 键；
 *   - `userId` 为 JSON 字符串（LongToStringModule，如 "1234567890123456789" 带引号）。
 * 会话持久化：键 `acme.auth.session.v1`；字段 `accessExpiresAtMs`/`refreshExpiresAtMs`
 * （epoch ms，须有限正数）。
 *
 * 装配：范本 mobile/tests/REQ20261114120110-authRegression.test.ts（store/fetch/envelope 口径）
 * ＋ mobile/tests/REQ20261119040838-qrLogin.test.tsx（RN 宿主标签替身渲染 LoginScreen 既有形态，
 *   C 端手机验证码 Tab 为默认 Tab——qrLogin 用例均先点击 login-mode-employee 切离）。
 *
 * 【AMB 假设（低风险，失败即 TL 裁决；pitfall 10/14 宽容匹配口径）】
 *   - AMB-1：C 端登录表单控件经 testID/aria-label/placeholder 宽容正则定位
 *     （手机号 /phone|手机/、验证码 /captcha|验证码|code/、发送按钮 /获取验证码|发送验证码|获取验证/、
 *     主登录按钮排除 login-mode-* Tab 切换后匹配 /登\s*录|login/）；testID 具体字面量不钉。
 *   - AMB-2：发送验证码按钮存在则点击（void 端点 200 空体=成功）；不存在则直接提交登录。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createElement } from 'react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';

// ---- 原生/三方模块内存桩（先于 import 声明；照抄 qrLogin 装配） ----

// expo-secure-store（authStore import 链；secureMem 供落库断言）
const secureMem = vi.hoisted(() => new Map<string, string>());
vi.mock('expo-secure-store', () => ({
  getItemAsync: vi.fn(async (key: string) => secureMem.get(key) ?? null),
  setItemAsync: vi.fn(async (key: string, value: string) => {
    secureMem.set(key, value);
  }),
  deleteItemAsync: vi.fn(async (key: string) => {
    secureMem.delete(key);
  }),
}));

// AsyncStorage 内存桩（theme/useStore import 链兜底）
vi.mock('@react-native-async-storage/async-storage', () => {
  const store = new Map<string, string>();
  const api = {
    getItem: vi.fn(async (key: string) => store.get(key) ?? null),
    setItem: vi.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    removeItem: vi.fn(async (key: string) => {
      store.delete(key);
    }),
  };
  return { default: api, AsyncStorage: api };
});

// expo-camera 桩（LoginScreen 扫码入口 import 链兜底；用例不驱动相机）
vi.mock('expo-camera', () => ({
  CameraView: () => createElement('div', { 'data-testid': 'camera-preview' }),
  useCameraPermissions: () => [{ granted: true }, () => Promise.resolve({ granted: true })],
}));

// react-navigation 桩（LoginScreen 登录成功导航；由桩捕获，不导航真路由）
const navStub = vi.hoisted(() => ({
  navigate: vi.fn(),
  goBack: vi.fn(),
}));
vi.mock('@react-navigation/native', () => ({
  useNavigation: () => navStub,
}));

// react-native 宿主标签替身（qrLogin 既有形态：View→div / Text→span / Pressable→button[onPress→onClick]；
// TextInput→input[onChangeText→onChange 双向]；a11y/testID 落 aria-label/data-testid）
vi.mock('react-native', async () => {
  const { createElement, forwardRef } = await import('react');
  const splitProps = (props: Record<string, unknown>) => {
    const {
      accessibilityLabel,
      accessibilityRole,
      accessibilityState,
      accessibilityLiveRegion,
      testID,
      placeholderTextColor,
      keyboardType,
      autoComplete: autoCompleteProp,
      autoCapitalize,
      autoCorrect,
      secureTextEntry,
      maxLength,
      resizeMode,
      source,
      ...rest
    } = props;
    return {
      rest,
      ariaLabel: typeof accessibilityLabel === 'string' ? accessibilityLabel : undefined,
      testId: typeof testID === 'string' ? testID : undefined,
    };
  };
  const Pressable = forwardRef(function Pressable(props: Record<string, unknown>, ref: unknown) {
    const { onPress, ...restProps } = props;
    const { rest, ariaLabel, testId } = splitProps(restProps as Record<string, unknown>);
    return createElement('button', {
      ...rest,
      'aria-label': ariaLabel,
      'data-testid': testId,
      onPress: undefined,
      onClick: onPress,
      ref,
    });
  });
  return {
    View: forwardRef(function View(props: Record<string, unknown>, ref: unknown) {
      const { rest, ariaLabel, testId } = splitProps(props);
      return createElement('div', { ...rest, 'aria-label': ariaLabel, 'data-testid': testId, ref });
    }),
    Text: 'span',
    Pressable,
    ScrollView: 'div',
    TextInput: forwardRef(function TextInput(props: Record<string, unknown>, ref: unknown) {
      const { onChangeText, value, ...restProps } = props;
      const { rest, ariaLabel, testId } = splitProps(restProps as Record<string, unknown>);
      return createElement('input', {
        ...rest,
        value,
        'aria-label': ariaLabel,
        'data-testid': testId,
        onChange: typeof onChangeText === 'function'
          ? (e: { target: { value: string } }) => onChangeText(e.target.value)
          : undefined,
        ref,
      });
    }),
    Image: function Image(props: Record<string, unknown>) {
      const { rest, ariaLabel, testId } = splitProps(props);
      const source = props.source as { uri?: string } | undefined;
      return createElement('img', { ...rest, 'aria-label': ariaLabel, 'data-testid': testId, src: source?.uri });
    },
    KeyboardAvoidingView: 'div',
    SafeAreaView: 'div',
    StatusBar: () => null,
    ActivityIndicator: () => createElement('div', { 'data-testid': 'spinner' }),
    StyleSheet: { create: (styles: unknown) => styles, flatten: (styles: unknown) => styles },
    Platform: { OS: 'ios', select: (options: Record<string, unknown>) => options?.ios ?? options?.default },
  };
});

// ---- 被测对象（mock 声明之后 import） ----

import { ThemeProvider } from '../src/theme';
import { useAuthStore } from '../src/store/authStore';
import { apiFetch } from '../src/api/client';
import { LoginScreen } from '../src/screens/LoginScreen';

// React 18 act 环境（qrLogin 口径）
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const SESSION_KEY = 'acme.auth.session.v1';

// ---- 渲染替身（qrLogin 既有形态） ----

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

let container: HTMLElement;
let root: Root | undefined;

async function render(node: React.ReactNode): Promise<void> {
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => {
    root!.render(node);
  });
}

async function unmount(): Promise<void> {
  const r = root;
  root = undefined;
  if (!r) return;
  await act(async () => {
    r.unmount();
  });
  container.remove();
}

/** 受控输入模拟：原生 value setter + input 事件（React onChangeText 才会收到）。 */
async function type(el: HTMLElement, value: string): Promise<void> {
  await act(async () => {
    const input = el as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
  });
}

async function click(el: HTMLElement): Promise<void> {
  await act(async () => {
    el.click();
    await flush();
  });
}

/** 轮询断言（mobile 无 @testing-library；poll 内断言失败即继续等待至超时抛出）。 */
async function waitForAssert(assert: () => void, message: string, timeoutMs = 5000): Promise<void> {
  const start = Date.now();
  let lastError: unknown = null;
  for (;;) {
    try {
      assert();
      return;
    } catch (error) {
      lastError = error;
    }
    if (Date.now() - start > timeoutMs) {
      throw new Error(`${message}（等待超时）${lastError instanceof Error ? `：${lastError.message}` : ''}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

// ---- 登录表单定位（AMB-1：testID/aria/placeholder 宽容正则；失败附 DOM 诊断） ----

function describeForm(): string {
  const inputs = Array.from(container.querySelectorAll('input')).map((el) => {
    const i = el as HTMLInputElement;
    return `[input testid=${i.getAttribute('data-testid') ?? '-'} aria=${i.getAttribute('aria-label') ?? '-'} placeholder=${i.getAttribute('placeholder') ?? '-'}]`;
  });
  const buttons = Array.from(container.querySelectorAll('button')).map((el) => {
    const b = el as HTMLButtonElement;
    return `[button testid=${b.getAttribute('data-testid') ?? '-'} aria=${b.getAttribute('aria-label') ?? '-'} text=${(b.textContent ?? '').trim()}]`;
  });
  return `inputs=[${inputs.join(' ')}]; buttons=[${buttons.join(' ')}]`;
}

const inputHay = (el: HTMLInputElement): string =>
  `${el.getAttribute('data-testid') ?? ''} ${el.getAttribute('aria-label') ?? ''} ${el.getAttribute('placeholder') ?? ''}`;

const buttonHay = (el: HTMLButtonElement): string =>
  `${el.getAttribute('data-testid') ?? ''} ${el.getAttribute('aria-label') ?? ''} ${(el.textContent ?? '').trim()}`;

function findInput(pattern: RegExp): HTMLInputElement {
  const el = Array.from(container.querySelectorAll('input') as NodeListOf<HTMLInputElement>)
    .find((i) => pattern.test(inputHay(i)));
  if (!el) throw new Error(`登录表单输入框未找到 ${pattern}；DOM=${describeForm()}`);
  return el;
}

/** 主登录按钮：排除 login-mode-* Tab 切换钮后匹配 /登\s*录|login/；精确文案优先。 */
function findLoginButton(): HTMLButtonElement {
  const candidates = Array.from(container.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
    .filter((b) => !(b.getAttribute('data-testid') ?? '').startsWith('login-mode') && /登\s*录|login/i.test(buttonHay(b)));
  if (!candidates.length) throw new Error(`登录按钮未找到；DOM=${describeForm()}`);
  const exact = candidates.find((b) => /^(登\s*录|登录\/注册|login)$/i.test((b.textContent ?? '').trim())
    || /^(登\s*录|登录\/注册|login)$/i.test(b.getAttribute('aria-label') ?? ''));
  return exact ?? candidates[0]!;
}

/** 发送验证码按钮（AMB-2：存在则点击；不存在返回 null 直接提交登录）。 */
function findSendCaptchaButton(): HTMLButtonElement | null {
  return Array.from(container.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
    .find((b) => /获取验证码|发送验证码|获取验证|send.{0,6}captcha/i.test(buttonHay(b))) ?? null;
}

// ---- fetch 桩与 wire fixtures ----

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsTo = (fetchMock: ReturnType<typeof vi.fn>, pathname: string, method?: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname === pathname && (method ? r.method === method : true));

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const unauthorized = () => new Response(
  JSON.stringify({ success: false, code: 'UNAUTHORIZED', message: '令牌过期' }), { status: 401 },
);

/**
 * C 端 AppTokenIssued wire 形态 fixture（钉死 wire golden；日期取运行时未来值保会话有效，
 * 键集/形态与 authWireContract.test.ts 的 TOKEN_WIRE 逐键一致）：
 * accessExpiresAt/refreshExpiresAt ISO 串、userId JSON 字符串（雪花）、无 expiresAt 键。
 */
const issuedWire = () => ({
  accessToken: 'access-wire-234158',
  refreshToken: 'refresh-wire-234158',
  accessExpiresAt: new Date(Date.now() + 7_200_000).toISOString(),
  refreshExpiresAt: new Date(Date.now() + 14 * 86_400_000).toISOString(),
  userId: '1234567890123456789',
  phone: '13800138000',
});

/** 旧键漂移 fixture（负例面）：accessExpiresAt 误为 expiresAt——20261128 前前端错声明所期望的形态。 */
const issuedLegacy = () => ({
  accessToken: 'access-legacy-234158',
  refreshToken: 'refresh-legacy-234158',
  expiresAt: new Date(Date.now() + 7_200_000).toISOString(),
  refreshExpiresAt: new Date(Date.now() + 14 * 86_400_000).toISOString(),
  userId: '1234567890123456789',
  phone: '13800138000',
});

/** 走 LoginScreen C 端手机验证码登录全链路（渲染 → 填手机号/验证码 → 提交）。 */
async function loginViaScreen(): Promise<void> {
  await render(createElement(ThemeProvider, null, createElement(LoginScreen)));
  await type(findInput(/phone|手机/i), '13800138000');
  const send = findSendCaptchaButton();
  if (send) await click(send);
  await type(findInput(/captcha|验证码|code/i), '654321');
  await click(findLoginButton());
  await act(async () => { await flush(); });
}

// —— 范本口径：AuthSession 播种（AC-2 given：C 端会话存在且 refresh 未本地过期） ——
const seedSession = (accessToken = 'access-stale') => {
  useAuthStore.getState().setSession({
    accessToken,
    refreshToken: 'refresh-1',
    accessExpiresAtMs: Date.now() + 1_800_000,
    refreshExpiresAtMs: Date.now() + 12_096_000_000,
    userId: '7',
    phone: '13800138000',
  });
};

beforeEach(() => {
  vi.unstubAllGlobals();
  secureMem.clear();
  useAuthStore.getState().clear();
  navStub.navigate.mockClear();
  navStub.goBack.mockClear();
});

afterEach(async () => {
  vi.unstubAllGlobals();
  await unmount();
  secureMem.clear();
  useAuthStore.getState().clear();
});

// ————— AC-1：真实 wire（含 accessExpiresAt）经 LoginScreen 构造会话 → 有限正数毫秒 —————
describe('REQ20261127234158 C 端登录会话过期毫秒（AC-1）', () => {
  it('should_parse_access_expires_at_wire_key_to_finite_positive_session_ms_when_c_side_login_succeeds', async () => {
    const issued = issuedWire();
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      if (r.url.pathname === '/app/v1/auth/tokens' && r.method === 'POST') return envelope(issued);
      if (r.url.pathname.includes('/captcha') && r.method === 'POST') return new Response('', { status: 200 });
      return envelope(null);
    });
    vi.stubGlobal('fetch', fetchMock);

    await loginViaScreen();

    // 登录请求确实发生（负例对照面：消费的是响应 wire，不是请求缺席）
    expect(callsTo(fetchMock, '/app/v1/auth/tokens', 'POST'), 'C 端登录 POST /app/v1/auth/tokens').toHaveLength(1);
    // fixture 自检：C 端 wire golden 不含 expiresAt 键（B 端 AdminTokenIssued.expiresAt 为正确契约不在此列）
    expect('expiresAt' in issued).toBe(false);

    // LoginScreen 构造会话：accessExpiresAtMs = Date.parse(issued.accessExpiresAt)（有限正数，非 NaN）
    await waitForAssert(() => {
      expect(useAuthStore.getState().session).not.toBeNull();
    }, '登录后会话应写入 authStore');
    const session = useAuthStore.getState().session!;
    const expectedMs = Date.parse(issued.accessExpiresAt);
    expect(session.accessToken).toBe('access-wire-234158');
    expect(session.userId, 'userId wire 为 JSON 字符串（雪花）').toBe('1234567890123456789');
    expect(session.accessExpiresAtMs).toBe(expectedMs);
    expect(Number.isFinite(session.accessExpiresAtMs) && session.accessExpiresAtMs > 0,
      'accessExpiresAtMs 应为有限正数（非 NaN）').toBe(true);
    expect(session.refreshExpiresAtMs).toBe(Date.parse(issued.refreshExpiresAt));
    expect(Number.isFinite(session.refreshExpiresAtMs) && session.refreshExpiresAtMs > 0).toBe(true);

    // 落库：SecureStore acme.auth.session.v1 的毫秒字段与会话一致（有限正数）
    const persisted = JSON.parse(secureMem.get(SESSION_KEY)!) as {
      accessToken: string; accessExpiresAtMs: number; refreshExpiresAtMs: number;
    };
    expect(persisted.accessToken).toBe('access-wire-234158');
    expect(persisted.accessExpiresAtMs).toBe(expectedMs);
    expect(Number.isFinite(persisted.accessExpiresAtMs) && persisted.accessExpiresAtMs > 0).toBe(true);
  });

  it('should_yield_non_finite_session_expiry_when_wire_fixture_uses_legacy_expires_at_key', async () => {
    // 负例（AC-1 漂移根因面）：wire 若仍按旧键 expiresAt 下发，C 端按 accessExpiresAt 解析取不到值
    // → Date.parse(undefined)=NaN，过期判定/刷新轮换全错——本用例钉死该消费方向不可回退。
    const legacy = issuedLegacy();
    expect('expiresAt' in legacy, 'fixture 自检：旧键形态含 expiresAt').toBe(true);
    expect('accessExpiresAt' in legacy).toBe(false);
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      if (r.url.pathname === '/app/v1/auth/tokens' && r.method === 'POST') return envelope(legacy);
      if (r.url.pathname.includes('/captcha') && r.method === 'POST') return new Response('', { status: 200 });
      return envelope(null);
    });
    vi.stubGlobal('fetch', fetchMock);

    await loginViaScreen();

    expect(callsTo(fetchMock, '/app/v1/auth/tokens', 'POST')).toHaveLength(1);
    const session = useAuthStore.getState().session;
    const ms = session?.accessExpiresAtMs;
    // 旧键 fixture：accessExpiresAtMs 不得为有限正数（NaN 或会话被拒收）
    expect(typeof ms === 'number' && Number.isFinite(ms) && ms > 0,
      '旧键 expiresAt fixture 下 accessExpiresAtMs 不得为有限正数').toBe(false);
    expect(ms === undefined || Number.isNaN(ms),
      'accessExpiresAtMs 应为 NaN（Date.parse(undefined)）或会话未写入').toBe(true);
  });
});

// ————— AC-2：静默刷新 envelope 按 accessExpiresAt 解析，renewed 落库有限值 —————
describe('REQ20261127234158 静默刷新轮换落库（AC-2）', () => {
  it('should_renew_session_with_wire_expiry_ms_when_access_token_rotated_after_401', async () => {
    seedSession('access-stale');
    const refreshWire = {
      accessToken: 'access-rotated-234158',
      refreshToken: 'refresh-rotated-234158',
      accessExpiresAt: new Date(Date.now() + 7_200_000).toISOString(),
      refreshExpiresAt: new Date(Date.now() + 14 * 86_400_000).toISOString(),
    };
    // ① 业务请求 401 → ② POST /app/v1/auth/tokens/refresh（真实 wire 形态） → ③ 业务重试成功
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      if (r.url.pathname === '/app/v1/auth/tokens/refresh' && r.method === 'POST') return envelope(refreshWire);
      if (r.url.pathname === '/app/v1/auth/tokens/current' && r.method === 'DELETE') {
        return callsTo(fetchMock, '/app/v1/auth/tokens/refresh', 'POST').length === 0
          ? unauthorized()
          : envelope({ ok: true });
      }
      return envelope(null);
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiFetch<unknown>('/app/v1/auth/tokens/current', { method: 'DELETE' }))
      .resolves.toEqual({ ok: true });

    // 轮换链路：原请求 401 → 刷新端点 → 业务重试，共 3 次调用
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(callsTo(fetchMock, '/app/v1/auth/tokens/refresh', 'POST'), '静默刷新打 refresh 端点').toHaveLength(1);

    // renewed 落库：刷新 envelope 按 accessExpiresAt/refreshExpiresAt 解析为有限正数毫秒（非 NaN）
    const session = useAuthStore.getState().session!;
    expect(session.accessToken).toBe('access-rotated-234158');
    expect(session.refreshToken).toBe('refresh-rotated-234158');
    expect(session.accessExpiresAtMs).toBe(Date.parse(refreshWire.accessExpiresAt));
    expect(Number.isFinite(session.accessExpiresAtMs) && session.accessExpiresAtMs > 0).toBe(true);
    expect(session.refreshExpiresAtMs).toBe(Date.parse(refreshWire.refreshExpiresAt));
    expect(Number.isFinite(session.refreshExpiresAtMs) && session.refreshExpiresAtMs > 0).toBe(true);

    // 落库：SecureStore 持久化轮换结果（重启可恢复新令牌对与毫秒字段）
    const persisted = JSON.parse(secureMem.get(SESSION_KEY)!) as {
      accessToken: string; accessExpiresAtMs: number; refreshExpiresAtMs: number;
    };
    expect(persisted.accessToken).toBe('access-rotated-234158');
    expect(persisted.accessExpiresAtMs).toBe(Date.parse(refreshWire.accessExpiresAt));
    expect(persisted.refreshExpiresAtMs).toBe(Date.parse(refreshWire.refreshExpiresAt));
  });
});
