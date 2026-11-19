/**
 * REQ-20261119-082531（个人中心）web 端契约用例：
 * - AC-7 /profile 页：资料/安全/通知三卡渲染（Updater 卡留 Settings 不动，契约⑦括注）；
 * - 头像两步上传：先 POST /admin/v1/infra/me/avatar（multipart）拿 url，再随 PUT /me 提交 avatarUrl；
 * - 自助改密：成功提示「其他设备已下线」；OLD_PASSWORD_INVALID 错误文案；
 * - 会话列表：current 标记 + 下线其他/逐条下线（DELETE /admin/v1/auth/me/sessions/*）；
 * - 通知开关：messagePush PUT 提交；
 * - 入口：用户菜单「个人中心」→ /profile（NAV/routes 注册）；
 * - api 层路径/载荷（core systemApi/authApi 自助面）。
 *
 * 装配照范本 REQ20261114120101-nav.test.tsx（AntApp + fetch 桩 + me 播种 + 浏览器桩）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { ProfilePage } from '../src/pages/ProfilePage';
import {
  authApi,
  systemApi,
  useAuthStore,
  useMeStore,
} from '@acme/core';

vi.setConfig({ testTimeout: 20000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);
const failEnvelope = (code: string, message: string, status = 200) => new Response(
  JSON.stringify({ success: false, code, message, data: null }), { status },
);

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: 'admin@acme.local', phone: null,
  avatarUrl: null,
  status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const selfSessions = [
  { tokenId: 'jti-self-current', ip: '10.0.0.8', issuedAt: '2026-11-19T01:00:00Z', lastActiveAt: '2026-11-19T02:30:00Z', current: true },
  { tokenId: 'jti-other-device', ip: '10.0.0.21', issuedAt: '2026-11-18T09:00:00Z', lastActiveAt: '2026-11-19T01:40:00Z', current: false },
];

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' || input instanceof URL
    ? new URL(String(input), 'http://localhost')
    : new URL((input as Request).url);
  return { url, method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
};

const callsTo = (fetchMock: ReturnType<typeof vi.fn>, pathname: string, method?: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname === pathname && (method ? r.method === method : true));

const parseBody = (body: BodyInit | null | undefined): Record<string, unknown> => {
  if (!body || typeof body !== 'string') {
    return {};
  }
  try {
    return JSON.parse(body) as Record<string, unknown>;
  } catch {
    return {};
  }
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
};

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: '1001',
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** ProfilePage 单页装配：播种会话 + me 画像 + 伴随端点 stub（prefs/sessions）。 */
const renderProfilePage = async (options: {
  prefs?: unknown;
  sessions?: unknown;
  routes?: Record<string, (r: { url: URL; method: string }) => Response | undefined>;
} = {}) => {
  seedSession();
  const { prefs = { messagePush: false }, sessions = selfSessions, routes = {} } = options;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me' && r.method === 'GET') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: ['system:user:list'], menus: [] });
    }
    if (p === '/admin/v1/system/me/preferences' && r.method === 'GET') return envelope(prefs);
    if (p === '/admin/v1/auth/me/sessions' && r.method === 'GET') return envelope(sessions);
    for (const [fragment, respond] of Object.entries(routes)) {
      if (p.includes(fragment)) {
        const custom = respond({ url: r.url, method: r.method });
        if (custom) return custom;
      }
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  await useMeStore.getState().load();
  render(<AntApp><ProfilePage /></AntApp>);
  return { fetchMock };
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
});

afterEach(async () => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— AC-7 四卡渲染（资料/安全/通知三卡在 /profile；Updater 卡留 Settings） —————

describe('REQ20261119082531 AC-7 /profile 页渲染', () => {
  it('should_render_profile_security_notification_cards_when_profilePageMounted', async () => {
    await renderProfilePage();
    expect(await screen.findByText('个人资料')).toBeTruthy();
    expect(screen.getByText('账户安全')).toBeTruthy();
    expect(screen.getByText('通知偏好')).toBeTruthy();
    // 页面标题与三卡并存；无Updater 卡（契约⑦：Updater 卡留 Settings）
    expect(screen.getByText('个人中心')).toBeTruthy();
    expect(screen.queryByText('检查更新')).toBeNull();
  });

  it('should_render_me_fields_when_meLoaded', async () => {
    await renderProfilePage();
    const nickname = await screen.findByLabelText('昵称') as HTMLInputElement;
    await waitFor(() => {
      expect(nickname.value).toBe('超级管理员');
    });
    expect((screen.getByLabelText('邮箱') as HTMLInputElement).value).toBe('admin@acme.local');
    // 头像回退昵称首字符（契约⑦：无 avatarUrl 显示首字符 accent 底）
    expect(screen.getByText('超')).toBeTruthy();
  });
});

// ————— AC-7 头像两步上传（先 POST /infra/me/avatar 拿 url → 随 PUT /me 提交 avatarUrl） —————

describe('REQ20261119082531 头像两步上传', () => {
  it('should_uploadAvatarThenPutMeWithAvatarUrl_when_profileSaved', async () => {
    const { fetchMock } = await renderProfilePage({
      routes: {
        '/infra/me/avatar': () => envelope({
          id: '9001', originalName: 'a.png', size: 10, contentType: 'image/png',
          url: '/files/avatar-9001.png', bizType: 'avatar', bizId: '1001', createdAt: '2026-11-19T03:00:00Z',
        }),
        // PUT /me 成功返回最新 me（avatarUrl 已落库）
        '/system/me': () => envelope({ user: { ...meUser, avatarUrl: '/files/avatar-9001.png' }, roles: ['super_admin'], permissions: [], menus: [] }),
      },
    });
    await screen.findByLabelText('昵称');
    await waitFor(() => {
      expect((screen.getByLabelText('昵称') as HTMLInputElement).value).toBe('超级管理员');
    });

    // 第一步：选择图片 → POST /admin/v1/infra/me/avatar（multipart，file 字段）
    const file = new File(['avatar-bytes'], 'a.png', { type: 'image/png' });
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    await waitFor(() => {
      fireEvent.change(input, { target: { files: [file] } });
      expect(callsTo(fetchMock, '/admin/v1/infra/me/avatar', 'POST')).toHaveLength(1);
    });
    const uploadBody = callsTo(fetchMock, '/admin/v1/infra/me/avatar', 'POST')[0]!.body;
    expect(uploadBody instanceof FormData).toBe(true);
    const picked = (uploadBody as FormData).get('file') as File | null;
    expect(picked?.name).toBe('a.png');
    // 预览态出现（头像已上传提示）
    expect(await screen.findByText('头像已上传，保存资料后生效')).toBeTruthy();

    // 第二步：保存资料 → PUT /admin/v1/system/me 载荷含 avatarUrl（两步流程闭环）
    fireEvent.click(screen.getByRole('button', { name: '保存资料' }));
    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/system/me', 'PUT')).toHaveLength(1);
    });
    const putBody = parseBody(callsTo(fetchMock, '/admin/v1/system/me', 'PUT')[0]!.body);
    expect(putBody.nickname).toBe('超级管理员');
    expect(putBody.email).toBe('admin@acme.local');
    expect(putBody.avatarUrl).toBe('/files/avatar-9001.png');
    // 保存成功后刷新 meStore（load 再次 GET /me）
    expect(callsTo(fetchMock, '/admin/v1/system/me', 'GET').length).toBeGreaterThanOrEqual(2);
    expect(await screen.findByText('个人资料已保存')).toBeTruthy();
  });
});

// ————— AC-3（web 面）自助改密：成功提示与 OLD_PASSWORD_INVALID 文案 —————

const fillPasswordForm = async () => {
  const nickname = await screen.findByLabelText('昵称');
  expect(nickname).toBeTruthy();
  fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: 'old-123' } });
  fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'new-123456' } });
  fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'new-123456' } });
  fireEvent.click(screen.getByRole('button', { name: '修改密码' }));
};

describe('REQ20261119082531 自助改密', () => {
  it('should_putSelfPasswordAndToastOthersSignedOut_when_passwordChangeSucceeds', async () => {
    const { fetchMock } = await renderProfilePage();
    await fillPasswordForm();

    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')).toHaveLength(1);
    });
    expect(parseBody(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')[0]!.body)).toEqual({
      oldPassword: 'old-123',
      newPassword: 'new-123456',
    });
    // 成功提示「其他设备已下线」+ 会话列表刷新
    expect(await screen.findByText('密码已修改，其他设备已下线')).toBeTruthy();
    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/auth/me/sessions', 'GET').length).toBeGreaterThanOrEqual(2);
    });
  });

  it('should_showOldPasswordInvalidMessage_when_passwordChangeRejected', async () => {
    const { fetchMock } = await renderProfilePage({
      routes: {
        '/auth/me/password': () => failEnvelope('OLD_PASSWORD_INVALID', '当前密码不正确', 200),
      },
    });
    await fillPasswordForm();

    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')).toHaveLength(1);
    });
    expect(await screen.findByText('当前密码不正确')).toBeTruthy();
  });

  it('should_blockSubmitWithMismatchToast_when_confirmPasswordDiffers', async () => {
    const { fetchMock } = await renderProfilePage();
    await screen.findByLabelText('昵称');
    fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: 'old-123' } });
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'new-123456' } });
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'other-123456' } });
    fireEvent.click(screen.getByRole('button', { name: '修改密码' }));

    expect(await screen.findByText('两次输入的新密码不一致')).toBeTruthy();
    expect(callsTo(fetchMock, '/admin/v1/auth/me/password', 'PUT')).toHaveLength(0);
  });
});

// ————— AC-4（web 面）会话列表：current 标记 + 下线其他/逐条下线 —————

describe('REQ20261119082531 在线会话管理', () => {
  it('should_markCurrentSessionAndListOthers_when_sessionsLoaded', async () => {
    await renderProfilePage();
    await screen.findByText('jti-self-current');
    expect(screen.getByText('jti-other-device')).toBeTruthy();
    // current 行带「当前」标签；其他行有逐条下线按钮
    expect(screen.getByText('当前')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: '下线 jti-other-device' }).length).toBe(1);
  });

  it('should_deleteSessionsOthers_when_kickOthersClicked', async () => {
    const { fetchMock } = await renderProfilePage();
    await screen.findByText('jti-other-device');

    fireEvent.click(screen.getByRole('button', { name: '下线其他设备' }));
    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/auth/me/sessions/others', 'DELETE')).toHaveLength(1);
    });
    expect(await screen.findByText('其他设备已下线')).toBeTruthy();
  });

  it('should_kickSingleSession_when_rowKickClicked', async () => {
    const { fetchMock } = await renderProfilePage();
    await screen.findByText('jti-other-device');

    fireEvent.click(screen.getByRole('button', { name: '下线 jti-other-device' }));
    await waitFor(() => {
      expect(callsTo(fetchMock, '/admin/v1/auth/me/sessions/jti-other-device', 'DELETE')).toHaveLength(1);
    });
    expect(await screen.findByText('会话已下线')).toBeTruthy();
  });
});

// ————— AC-5（web 面）通知偏好开关提交 —————

describe('REQ20261119082531 通知偏好', () => {
  it('should_putMessagePushTrue_when_switchToggledOn', async () => {
    const { fetchMock } = await renderProfilePage();
    const sw = await screen.findByRole('switch', { name: 'APP 消息推送' });
    // AntD Switch 以 aria-checked 表达开关态
    expect(sw.getAttribute('aria-checked')).toBe('false');

    fireEvent.click(sw);
    await waitFor(() => {
      const puts = callsTo(fetchMock, '/admin/v1/system/me/preferences', 'PUT');
      expect(puts).toHaveLength(1);
      expect(parseBody(puts[0]!.body)).toEqual({ messagePush: true });
    });
    expect(await screen.findByText('通知偏好已保存')).toBeTruthy();
    await waitFor(() => {
      expect(sw.getAttribute('aria-checked')).toBe('true');
    });
  });
});

// ————— AC-7 入口：用户菜单「个人中心」→ /profile —————

describe('REQ20261119082531 个人中心入口', () => {
  /** App 整棵装配（tabs.test renderAppAt 口径）；returns 下拉可见菜单项集合取用器。 */
  const renderAppAt = async (hash: string) => {
    seedSession();
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      const p = r.url.pathname;
      if (p === '/admin/v1/system/me' && r.method === 'GET') {
        return envelope({ user: meUser, roles: ['super_admin'], permissions: [], menus: [] });
      }
      if (p === '/admin/v1/auth/me/sessions') return envelope(selfSessions);
      if (p === '/admin/v1/system/me/preferences') return envelope({ messagePush: false });
      return envelope(null); // Dashboard 等伴随请求
    });
    vi.stubGlobal('fetch', fetchMock);
    await useMeStore.getState().load();
    window.location.hash = hash;
    render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
    return { fetchMock };
  };

  /** 仅取用户菜单下拉可见项（antd 关闭菜单保留隐藏 DOM，侧边导航同名项需排除）。 */
  const visibleUserMenuItems = (): HTMLElement[] =>
    Array.from(document.querySelectorAll('.ant-dropdown:not(.ant-dropdown-hidden) [role="menuitem"]')) as HTMLElement[];

  it('should_navigateToProfile_when_userMenuEntryClicked', async () => {
    await renderAppAt('#/');
    // 等应用就绪（页签条渲染）
    await waitFor(() => {
      expect(document.querySelector('[role="tablist"]')).toBeTruthy();
    }, { timeout: 10000 });

    // 用户菜单出现「个人中心」项（契约⑦：入口自用户菜单）
    fireEvent.mouseEnter(screen.getByRole('button', { name: '打开用户菜单' }));
    await waitFor(() => {
      expect(visibleUserMenuItems().some((el) => el.textContent === '个人中心')).toBe(true);
    }, { timeout: 5000 });
    fireEvent.click(visibleUserMenuItems().find((el) => el.textContent === '个人中心')!);

    await waitFor(() => {
      expect(window.location.hash).toBe('#/profile');
    }, { timeout: 10000 });
    // /profile 路由渲染（ProfilePage 三卡，TabKeepAlive Suspense 承载懒加载）
    expect(await screen.findByText('个人资料', {}, { timeout: 10000 })).toBeTruthy();
  });
});

// ————— api 层路径/载荷（core systemApi/authApi 自助面） —————

describe('REQ20261119082531 个人中心 api 层', () => {
  const stubCapture = () => {
    const calls: Array<{ url: URL; method: string; body: BodyInit | null | undefined; headers: Headers }> = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' || input instanceof URL
        ? new URL(String(input), 'http://localhost')
        : new URL((input as Request).url);
      calls.push({ url, method: (init?.method ?? 'GET').toUpperCase(), body: init?.body, headers: new Headers(init?.headers) });
      return envelope(null);
    }));
    return calls;
  };

  it('should_call_profile_endpoints_by_contract_paths_when_systemApiInvoked', async () => {
    const calls = stubCapture();

    await systemApi.meUpdate({ nickname: '新昵称', email: 'a@b.co', phone: '13800000000', avatarUrl: '/files/x.png' });
    await systemApi.mePreferences();
    await systemApi.mePreferencesUpdate({ messagePush: true });
    await systemApi.pushTokenRegister({ token: 'expo-token-1', platform: 'android' });
    await systemApi.pushTokenRemove('expo-token-1');

    const [putMe, getPrefs, putPrefs, postToken, deleteToken] = calls;
    expect(putMe!.url.pathname).toBe('/admin/v1/system/me');
    expect(putMe!.method).toBe('PUT');
    expect(JSON.parse(String(putMe!.body))).toEqual({ nickname: '新昵称', email: 'a@b.co', phone: '13800000000', avatarUrl: '/files/x.png' });
    expect(getPrefs!.url.pathname).toBe('/admin/v1/system/me/preferences');
    expect(getPrefs!.method).toBe('GET');
    expect(putPrefs!.method).toBe('PUT');
    expect(JSON.parse(String(putPrefs!.body))).toEqual({ messagePush: true });
    expect(postToken!.url.pathname).toBe('/admin/v1/system/me/push-tokens');
    expect(postToken!.method).toBe('POST');
    expect(JSON.parse(String(postToken!.body))).toEqual({ token: 'expo-token-1', platform: 'android' });
    expect(deleteToken!.method).toBe('DELETE');
    expect(deleteToken!.url.pathname).toBe('/admin/v1/system/me/push-tokens');
    expect(deleteToken!.url.search).toBe('?token=expo-token-1');
  });

  it('should_postAvatarMultipartToInfraMeAvatar_when_meAvatarUploadCalled', async () => {
    const calls = stubCapture();
    await systemApi.meAvatarUpload(new File(['bytes'], 'me.png', { type: 'image/png' }));
    expect(calls).toHaveLength(1);
    expect(calls[0]!.url.pathname).toBe('/admin/v1/infra/me/avatar');
    expect(calls[0]!.method).toBe('POST');
    expect(calls[0]!.body instanceof FormData).toBe(true);
    expect(((calls[0]!.body as FormData).get('file') as File).name).toBe('me.png');
  });

  it('should_call_self_account_endpoints_by_contract_paths_when_authApiInvoked', async () => {
    const calls = stubCapture();

    await authApi.selfPassword({ oldPassword: 'old-1', newPassword: 'new-123456' });
    await authApi.selfSessions();
    await authApi.selfKickSession('jti-other-device');
    await authApi.selfKickOthers();

    const [putPwd, getSessions, kickOne, kickOthers] = calls;
    expect(putPwd!.url.pathname).toBe('/admin/v1/auth/me/password');
    expect(putPwd!.method).toBe('PUT');
    expect(JSON.parse(String(putPwd!.body))).toEqual({ oldPassword: 'old-1', newPassword: 'new-123456' });
    expect(getSessions!.url.pathname).toBe('/admin/v1/auth/me/sessions');
    expect(getSessions!.method).toBe('GET');
    expect(kickOne!.method).toBe('DELETE');
    expect(kickOne!.url.pathname).toBe('/admin/v1/auth/me/sessions/jti-other-device');
    expect(kickOthers!.method).toBe('DELETE');
    expect(kickOthers!.url.pathname).toBe('/admin/v1/auth/me/sessions/others');
  });
});

// ————— i18n：profile 域 zh/en 成对（AC-10） —————

function leafPaths(node: unknown, prefix = ''): string[] {
  if (node === null || typeof node !== 'object') {
    return [prefix];
  }
  return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) =>
    leafPaths(v, prefix ? `${prefix}.${k}` : k),
  );
}

describe('REQ20261119082531 i18n profile 域', () => {
  it('should_pair_zh_and_en_profile_resource_shapes_when_profileKeysAdded', async () => {
    const { i18n } = await import('@acme/core');
    const data = (i18n as unknown as { store: { data: Record<string, { translation: Record<string, unknown> }> } }).store.data;
    const zh = leafPaths(data['zh-CN']!.translation.profile).sort();
    const en = leafPaths(data['en-US']!.translation.profile).sort();
    expect(zh.length).toBeGreaterThan(0);
    expect(en).toEqual(zh);
    expect(i18n.t('profile.title')).toBe('个人中心');
  });
});
