import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 原生模块在 Node/vitest 下不可解析与加载：内存桩替代（范本：mobile/tests/auth.test.ts）
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
import { useMeStore } from '../src/store/meStore';

/**
 * REQ-20261114-120110 AC-2：mobile meStore 接入（TL 裁决 AMB-2 镜像方案）。
 * 断言面=mobile 镜像 `src/store/meStore.ts`，接口语义对齐 core useMeStore
 * （me/loading/loaded/error/load/clear）：
 *   ① 登录后 load() → 画像/角色/权限/菜单可用（GET /admin/v1/system/me 走 mobile apiFetch）；
 *   ② 403（已认证但不可用）→ 清 mobile 会话（SecureStore 一并清）回登录屏——
 *      mobile 无路由 hash，「回登录屏」由 App 门禁基于 session===null 条件渲染达成，
 *      故断言会话清空即等价断言回登录屏；
 *   ③ 非鉴权错误降级：保留本地会话身份渲染，不阻塞控制台（对齐 core 语义）；
 *   ④ loading 去重：并发 load 仅一次网络请求。
 */
describe('REQ20261114120110 mobile meStore（AC-2）', () => {
  const SESSION_KEY = 'acme.auth.session.v1';

  const meView = () => ({
    user: {
      id: '7',
      username: 'qa-admin',
      nickname: 'QA 管理员',
      email: 'qa@acme.dev',
      phone: '13800138000',
      status: 'ENABLED',
      builtIn: false,
      roleIds: ['1'],
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      version: '0',
    },
    roles: ['admin'],
    permissions: ['system:user:list', 'system:dict:list'],
    menus: [
      {
        id: 1,
        parentId: 0,
        menuName: '系统管理',
        menuType: 'DIRECTORY',
        path: null,
        component: null,
        icon: null,
        sortOrder: 1,
        visible: true,
        permissionCode: null,
        status: 'ENABLED',
        children: [],
        version: '0',
      },
    ],
  });

  const envelope = (data: unknown, status = 200) => new Response(
    JSON.stringify({ success: true, code: '0', message: null, data }), { status },
  );

  const forbidden = () => new Response(
    JSON.stringify({ success: false, code: 'FORBIDDEN', message: '账号已被停用' }), { status: 403 },
  );

  const serverError = () => new Response(
    JSON.stringify({ success: false, code: 'INTERNAL_ERROR', message: '服务暂不可用' }), { status: 500 },
  );

  const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

  const seedSession = () => {
    useAuthStore.getState().setSession({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      accessExpiresAtMs: Date.now() + 1_800_000,
      refreshExpiresAtMs: Date.now() + 12_096_000_000,
      userId: '7',
      phone: '13800138000',
    });
  };

  beforeEach(async () => {
    vi.unstubAllGlobals();
    secureMem.clear();
    useAuthStore.getState().clear();
    useMeStore.getState().clear();
    await flush();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('should_expose_profile_roles_permissions_menus_after_login', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => envelope(meView())));

    await useMeStore.getState().load();

    const { me, loading, loaded, error } = useMeStore.getState();
    // 画像/角色/权限/菜单四类数据可用（权限门禁与身份渲染的真实来源）
    expect(error).toBeNull();
    expect(loading).toBe(false);
    expect(loaded).toBe(true);
    expect(me?.user.username).toBe('qa-admin');
    expect(me?.user.phone).toBe('13800138000');
    expect(me?.roles).toEqual(['admin']);
    expect(me?.permissions).toContain('system:user:list');
    expect(me?.menus).toHaveLength(1);
    expect(me?.menus[0]?.menuName).toBe('系统管理');
  });

  it('should_clear_session_when_me_returns_403', async () => {
    seedSession();
    vi.stubGlobal('fetch', vi.fn(async () => forbidden()));

    await useMeStore.getState().load();

    // 403（已认证但不可用）→ 清会话回登录屏：mobile 会话（SecureStore 一并清）
    expect(useMeStore.getState().me).toBeNull();
    expect(useMeStore.getState().loaded).toBe(false);
    expect(useMeStore.getState().error).toBe('账号已被停用');
    expect(useAuthStore.getState().session).toBeNull();
    expect(secureMem.has(SESSION_KEY)).toBe(false);
  });

  it('should_keep_session_when_me_load_fails_with_non_auth_error', async () => {
    seedSession();
    vi.stubGlobal('fetch', vi.fn(async () => serverError()));

    await useMeStore.getState().load();

    // 非鉴权错误降级：error 上抛渲染，但本地会话保留（不阻塞控制台，对齐 core 语义）
    expect(useMeStore.getState().error).toBe('服务暂不可用');
    expect(useMeStore.getState().loaded).toBe(false);
    expect(useAuthStore.getState().session?.accessToken).toBe('access-1');
    expect(secureMem.get(SESSION_KEY)).not.toBeNull();
  });

  it('should_deduplicate_concurrent_loads_into_single_request', async () => {
    let resolveFetch: ((res: Response) => void) | undefined;
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => {
      resolveFetch = resolve;
    }));
    vi.stubGlobal('fetch', fetchMock);

    // 并发两次 load：loading 去重 → 仅一次网络请求
    const first = useMeStore.getState().load();
    const second = useMeStore.getState().load();
    resolveFetch!(envelope(meView()));
    await Promise.all([first, second]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useMeStore.getState().loaded).toBe(true);
    expect(useMeStore.getState().me?.user.username).toBe('qa-admin');
  });

  it('should_reset_me_state_on_clear', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => envelope(meView())));
    await useMeStore.getState().load();
    expect(useMeStore.getState().me).not.toBeNull();

    useMeStore.getState().clear();

    const { me, loading, loaded, error } = useMeStore.getState();
    expect(me).toBeNull();
    expect(loading).toBe(false);
    expect(loaded).toBe(false);
    expect(error).toBeNull();
  });
});
