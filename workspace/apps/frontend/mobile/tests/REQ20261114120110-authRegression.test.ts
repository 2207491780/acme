import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 原生模块在 Node/vitest 下不可解析与加载：以可读写内存桩替代（范本：mobile/tests/auth.test.ts）
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
import { apiFetch } from '../src/api/client';

/**
 * REQ-20261114-120110 AC-4：SecureStore 会话回归（既有 login-app 行为不因本期改动破坏）。
 *   ① given SecureStore 有效会话 when 重启（重新 hydrate）then 会话恢复并自动进入；
 *   ② given 会话 when 401 刷新轮换 then 轮换结果落 SecureStore，重启后仍有效。
 * 与既有 mobile/tests/auth.test.ts 的 hydrate/刷新用例互补：本文件从「重启」视角
 * 组合断言 持久化 → 进程重启 → 恢复 全链路。
 */

const SESSION_KEY = 'acme.auth.session.v1';

/** AuthSession 完整形状（范本：mobile/tests/auth.test.ts seedSession；毫秒字段与 AppTokenIssued 的 ISO expiresAt 不同名）。 */
const session = (accessToken = 'access-1') => ({
  accessToken,
  refreshToken: 'refresh-1',
  accessExpiresAtMs: Date.now() + 1_800_000,
  refreshExpiresAtMs: Date.now() + 12_096_000_000,
  userId: '7',
  phone: '13800138000',
});

const envelope = (data: unknown) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status: 200 },
);

const unauthorized = () => new Response(
  JSON.stringify({ success: false, code: 'UNAUTHORIZED', message: '令牌过期' }), { status: 401 },
);

/** 模拟进程重启：仅复位内存 store，SecureStore 持久化数据保留。 */
function simulateRestart(): void {
  useAuthStore.setState({ session: null, hydrated: false });
}

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

beforeEach(async () => {
  vi.unstubAllGlobals();
  secureMem.clear();
  useAuthStore.getState().clear();
  await flush();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('REQ20261114120110 SecureStore 会话回归（AC-4）', () => {
  it('should_restore_session_after_restart_when_secure_store_has_valid_session', async () => {
    // 登录写入会话（LoginScreen 既有链路：setSession → SecureStore 持久化）
    useAuthStore.getState().setSession(session());
    await flush();
    expect(secureMem.get(SESSION_KEY)).not.toBeNull();

    // 重启：内存态清空，SecureStore 数据保留
    simulateRestart();
    expect(useAuthStore.getState().session).toBeNull();
    expect(useAuthStore.getState().hydrated).toBe(false);

    // 冷启动 hydrate：会话恢复，应用自动进入已登录态
    await useAuthStore.getState().hydrate();
    expect(useAuthStore.getState().hydrated).toBe(true);
    expect(useAuthStore.getState().session?.accessToken).toBe('access-1');
    expect(useAuthStore.getState().session?.refreshToken).toBe('refresh-1');
    expect(useAuthStore.getState().session?.userId).toBe('7');
    expect(useAuthStore.getState().session?.phone).toBe('13800138000');
  });

  it('should_persist_rotated_tokens_after_refresh_so_next_restart_keeps_session', async () => {
    // 首次登录并持久化
    useAuthStore.getState().setSession(session('expired'));
    await flush();

    // 401 → 单飞刷新轮换成功 → 业务请求重试成功
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
    await expect(apiFetch<unknown>('/app/v1/auth/tokens/current', { method: 'DELETE' }))
      .resolves.toEqual({ ok: true });

    // 轮换结果已落 SecureStore（下次重启可恢复新令牌对）
    await flush();
    const persisted: unknown = JSON.parse(secureMem.get(SESSION_KEY)!);
    expect((persisted as { accessToken: string }).accessToken).toBe('access-2');
    expect((persisted as { refreshToken: string }).refreshToken).toBe('refresh-2');

    // 重启 → hydrate：轮换后的会话仍有效（自动进入，无需重新登录）
    simulateRestart();
    await useAuthStore.getState().hydrate();
    expect(useAuthStore.getState().session?.accessToken).toBe('access-2');
    expect(useAuthStore.getState().session?.refreshToken).toBe('refresh-2');
  });

  it('should_not_enter_logged_in_after_restart_when_refresh_token_expired_locally', async () => {
    // 持久化一个 refresh 令牌已本地过期的会话（重启后无法自动进入，应回登录屏语义）
    useAuthStore.getState().setSession({
      ...session(),
      refreshExpiresAtMs: Date.now() - 1_000,
    });
    await flush();
    simulateRestart();
    await useAuthStore.getState().hydrate();

    // 会话虽恢复，但刷新令牌已过期：任何带鉴权请求 401 时不发起注定失败的轮换，直接清会话
    const fetchMock = vi.fn(async () => unauthorized());
    vi.stubGlobal('fetch', fetchMock);
    await expect(apiFetch<unknown>('/app/v1/auth/tokens/current', { method: 'DELETE' }))
      .rejects.toMatchObject({ code: 'AUTH_REQUIRED' });
    expect(fetchMock).toHaveBeenCalledTimes(1); // 仅原请求；不发起轮换
    expect(useAuthStore.getState().session).toBeNull();
  });
});
