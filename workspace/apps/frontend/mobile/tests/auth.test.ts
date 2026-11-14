import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 原生模块在 Node/vitest 下不可解析与加载：以可读写内存桩替代（hydrate 测试需注入持久化数据）
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

import { useAuthStore } from '../src/store/authStore';
import { authApi } from '../src/api/auth';
import { apiFetch, isExpiringSoon } from '../src/api/client';

/** 移动端认证链路测试（login-app）：信封解包、401 单飞刷新重试、刷新失败清会话、续约判定。 */
describe('mobile auth', () => {
  const issued = () => ({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    expiresAt: new Date(Date.now() + 7_200_000).toISOString(),
    refreshExpiresAt: new Date(Date.now() + 12_096_000_000).toISOString(),
    userId: 7,
    phone: '13800138000',
  });

  const envelope = (data: unknown, status = 200) => new Response(
    JSON.stringify({ success: true, code: '0', message: null, data }), { status },
  );

  const unauthorized = () => new Response(
    JSON.stringify({ success: false, code: 'UNAUTHORIZED', message: '令牌过期' }), { status: 401 },
  );

  function seedSession(accessToken = 'expired') {
    useAuthStore.getState().setSession({
      accessToken,
      refreshToken: 'refresh-1',
      accessExpiresAtMs: Date.now() + 1_800_000,
      refreshExpiresAtMs: Date.now() + 12_096_000_000,
      userId: 7,
      phone: '13800138000',
    });
  }

  beforeEach(() => {
    vi.unstubAllGlobals();
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('should_login_and_return_dual_tokens', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => envelope(issued())));
    const result = await authApi.login('13800138000', '123456');
    expect(result.accessToken).toBe('access-1');
    expect(result.refreshToken).toBe('refresh-1');
    expect(result.expiresAt).toBeTruthy();
    // 会话写入由 LoginScreen 调 setSession 完成（接口层不触碰 store）
  });

  it('should_retry_once_after_refresh_on_401', async () => {
    seedSession('expired');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(envelope({
        accessToken: 'access-2',
        refreshToken: 'refresh-2',
        expiresAt: new Date(Date.now() + 7_200_000).toISOString(),
        refreshExpiresAt: new Date(Date.now() + 12_096_000_000).toISOString(),
      }))
      .mockResolvedValueOnce(envelope({ ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    const result = await apiFetch<unknown>('/app/v1/auth/tokens/current', { method: 'DELETE' });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(result).toEqual({ ok: true });
    expect(useAuthStore.getState().session?.accessToken).toBe('access-2');
  });

  it('should_clear_session_when_refresh_fails_on_401', async () => {
    seedSession('expired');
    vi.stubGlobal('fetch', vi.fn(async () => unauthorized()));
    await expect(apiFetch<unknown>('/app/v1/auth/tokens/current', { method: 'DELETE' }))
      .rejects.toMatchObject({ code: 'AUTH_REQUIRED' });
    expect(useAuthStore.getState().session).toBeNull();
  });

  it('should_surface_backend_error_message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ success: false, code: 'CAPTCHA_SEND_LIMITED', message: '验证码发送过于频繁，请稍后再试' }),
      { status: 429 },
    )));
    await expect(authApi.sendCaptcha('13800138000')).rejects.toMatchObject({
      status: 429,
      message: '验证码发送过于频繁，请稍后再试',
    });
  });

  it('should_report_expiring_soon_within_margin', () => {
    expect(isExpiringSoon(null)).toBe(true);
    expect(isExpiringSoon({ accessExpiresAtMs: Date.now() + 60_000 })).toBe(true);
    expect(isExpiringSoon({ accessExpiresAtMs: Date.now() + 1_800_000 })).toBe(false);
    expect(isExpiringSoon({ accessExpiresAtMs: Date.now() + 1_800_000 }, 0)).toBe(false);
  });

  it('should_reject_hydrate_when_persisted_field_missing', async () => {
    // 模拟安全存储中的损坏会话：缺 accessExpiresAtMs
    const { accessExpiresAtMs: _omit, ...broken } = {
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      accessExpiresAtMs: Date.now() + 1_800_000,
      refreshExpiresAtMs: Date.now() + 12_096_000_000,
      userId: 7,
      phone: '13800138000',
    };
    secureMem.set('acme.auth.session.v1', JSON.stringify(broken));
    await useAuthStore.getState().hydrate();
    expect(useAuthStore.getState().session).toBeNull();
    expect(useAuthStore.getState().hydrated).toBe(true);
  });

  it('should_hydrate_valid_session_from_secure_store', async () => {
    const valid = {
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      accessExpiresAtMs: Date.now() + 1_800_000,
      refreshExpiresAtMs: Date.now() + 12_096_000_000,
      userId: 7,
      phone: '13800138000',
    };
    secureMem.set('acme.auth.session.v1', JSON.stringify(valid));
    await useAuthStore.getState().hydrate();
    expect(useAuthStore.getState().session?.accessToken).toBe('access-1');
  });

  it('should_skip_refresh_when_refresh_token_locally_expired', async () => {
    seedSession('expired');
    const expired = useAuthStore.getState().session;
    useAuthStore.getState().setSession({ ...expired!, refreshExpiresAtMs: Date.now() - 1_000 });
    const fetchMock = vi.fn(async () => unauthorized());
    vi.stubGlobal('fetch', fetchMock);
    await expect(apiFetch<unknown>('/app/v1/auth/tokens/current', { method: 'DELETE' }))
      .rejects.toMatchObject({ code: 'AUTH_REQUIRED' });
    // 仅原请求 1 次；刷新令牌本地已过期 → 不发起轮换调用，直接清会话
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useAuthStore.getState().session).toBeNull();
  });

  it('should_resolve_send_captcha_when_void_endpoint_returns_empty_2xx', async () => {
    // 后端 void 端点按 Modules.md 3.1.1 豁免不出信封（HTTP 200 空体=成功），emptyAsSuccess 豁免不放行错误态
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 200 })));
    await expect(authApi.sendCaptcha('13800138000')).resolves.toBeUndefined();
  });

  it('should_resolve_logout_when_void_endpoint_returns_empty_2xx', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 200 })));
    await expect(authApi.logout()).resolves.toBeUndefined();
  });

  it('should_reject_empty_body_when_not_marked_empty_as_success', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 200 })));
    await expect(apiFetch<unknown>('/app/v1/auth/unknown')).rejects.toMatchObject({ code: 'EMPTY_BODY' });
  });

  it('should_reject_empty_body_when_error_status_even_with_empty_as_success', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })));
    await expect(authApi.sendCaptcha('13800138000')).rejects.toMatchObject({ code: 'EMPTY_BODY' });
  });
});
