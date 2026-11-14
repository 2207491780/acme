import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore, useMeStore } from '@acme/core';

/** 服务端画像加载（p1-me-sync）：成功写入 / 403 清会话回登录 / 降级不阻塞 / 并发单飞。 */
describe('meStore', () => {
  const meView = {
    user: {
      id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
      status: 'ENABLED' as const, builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
    },
    roles: ['super_admin'],
    permissions: ['tenant_list'],
    menus: [],
  };

  const envelope = (data: unknown, status = 200) => new Response(
    JSON.stringify({ success: true, code: '0', message: null, data }), { status },
  );

  beforeEach(() => {
    window.location.hash = '';
    useAuthStore.getState().clear();
    useMeStore.getState().clear();
    useAuthStore.getState().setSession({
      accessToken: 'token-1',
      expiresAtMs: Date.now() + 1_800_000,
      userId: 1001,
      username: 'admin',
      nickname: null,
      tenantCode: 'default',
    }, false);
  });

  it('should_load_me_and_update_loaded_state', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => envelope(meView)));
    await useMeStore.getState().load();
    expect(useMeStore.getState().me?.user.username).toBe('admin');
    expect(useMeStore.getState().me?.roles).toEqual(['super_admin']);
    expect(useMeStore.getState().loaded).toBe(true);
    expect(useMeStore.getState().error).toBeNull();
  });

  it('should_clear_auth_and_redirect_on_403', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ success: false, code: 'ACCOUNT_DISABLED', message: '账号已停用' }), { status: 403 },
    )));
    await useMeStore.getState().load();
    expect(useAuthStore.getState().session).toBeNull();
    expect(window.location.hash).toBe('#/login');
  });

  it('should_degrade_gracefully_on_server_error_without_clearing_session', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('oops', { status: 502 })));
    await useMeStore.getState().load();
    expect(useMeStore.getState().me).toBeNull();
    expect(useMeStore.getState().error).toContain('接口无响应体');
    expect(useAuthStore.getState().session).not.toBeNull();
    expect(window.location.hash).not.toBe('#/login');
  });

  it('should_deduplicate_concurrent_loads', async () => {
    const fetchMock = vi.fn(async () => {
      await new Promise((r) => setTimeout(r, 30));
      return envelope(meView);
    });
    vi.stubGlobal('fetch', fetchMock);
    await Promise.all([useMeStore.getState().load(), useMeStore.getState().load()]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
