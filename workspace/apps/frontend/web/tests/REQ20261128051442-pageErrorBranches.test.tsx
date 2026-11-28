/** @vitest-environment jsdom */
/**
 * REQ-20261128-051442（wire 契约·错误码统一）AC-4：页面错误分支**全码真命中**用例——
 * 补齐既有用例缺失的分支覆盖（既有 :251/:290 之外的全部缺口分支）：
 *   - UsersPage 重置密码：PASSWORD_POLICY_VIOLATED=platform-auth-02-0002（附录 A.4 02 段）；
 *   - TenantsPage 提交：TENANT_DEPTH_EXCEEDED=platform-system-06-0004 /
 *     TENANT_CYCLE=platform-system-06-0005（附录 A.3 06 段）；
 *   - ProfilePage：PROFILE_INVALID=platform-system-13-0001（A.3 13 段）/
 *     OLD_PASSWORD_INVALID=platform-auth-07-0001 / SAME_PASSWORD=platform-auth-07-0002（A.4 07 段）。
 *
 * stub 发码 = 附录 A 后端 wire 全码真值 → 页面分支判定（已全码化）真命中；
 * 断言锚定 i18n 转译文案（AC-4「用户可见转译文案」）。装配照既有范本：
 * profile（renderProfilePage）/ adminFrontendPages（renderPage+findRowByText）/
 * tenants（buildRoutes+currentDialog）；关闭态断言数据驱动（round2 裁决⑪口径）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import type { ComponentType } from 'react';
import { ProfilePage } from '../src/pages/ProfilePage';
import { TenantsPage } from '../src/pages/TenantsPage';
import { UsersPage } from '../src/pages/UsersPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 45000 });

const SUBMIT_BUTTON = /提\s*交|确\s*定|^OK$/i;
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const RESET_BUTTON = /重\s*置\s*密\s*码/;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const failureEnvelope = (code: string, message: string, status = 200) => new Response(
  JSON.stringify({ success: false, code, message, data: null }), { status },
);

type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (req.method ?? 'GET').toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

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

const seedMe = async (permissions: string[]): Promise<void> => {
  seedSession();
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

const renderPage = async (Component: ComponentType, permissions: string[], routes: Record<string, (r: ReturnType<typeof asRequest>) => Response> = {}) => {
  await seedMe(permissions);
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    for (const [part, handler] of Object.entries(routes)) {
      if (r.url.pathname.includes(part)) {
        return handler(r);
      }
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<AntApp><Component /></AntApp>);
  return { fetchMock };
};

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

/** 取「最后渲染」的可见 dialog（重开弹窗场景旧 modal 可能残留 DOM——数据驱动口径取最新）。 */
const currentDialog = () => {
  const dialogs = screen.getAllByRole('dialog');
  return dialogs[dialogs.length - 1] as HTMLElement;
};

const meUser = {
  id: '1001', username: 'admin', nickname: '超级管理员', email: 'admin@acme.local', phone: null,
  avatarUrl: null, status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
};

const selfSessions = [
  { tokenId: 'jti-self-current', ip: '10.0.0.8', issuedAt: '2026-11-19T01:00:00Z', lastActiveAt: '2026-11-19T02:30:00Z', current: true },
  { tokenId: 'jti-other-device', ip: '10.0.0.21', issuedAt: '2026-11-18T09:00:00Z', lastActiveAt: '2026-11-19T01:40:00Z', current: false },
];

/** ProfilePage 单页装配（profile.test.tsx 范本口径）：播种会话 + me 画像 + 伴随端点 stub。 */
const renderProfilePage = async (routes: Record<string, (r: { url: URL; method: string }) => Response> = {}) => {
  seedSession();
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (p === '/admin/v1/system/me' && r.method === 'GET') {
      return envelope({ user: meUser, roles: ['super_admin'], permissions: ['system:user:list'], menus: [] });
    }
    if (p === '/admin/v1/system/me/preferences' && r.method === 'GET') return envelope({ messagePush: false });
    if (p === '/admin/v1/auth/me/sessions' && r.method === 'GET') return envelope(selfSessions);
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

const fillPasswordForm = () => {
  fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: 'old-123' } });
  fireEvent.change(screen.getByLabelText('新密码'), { target: { value: 'new-123456' } });
  fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: 'new-123456' } });
  fireEvent.click(screen.getByRole('button', { name: '修改密码' }));
};

const USER_ROWS = [
  { id: '2001', username: 'alice', nickname: '爱丽丝', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [], createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0' },
  { id: '2002', username: 'bob', nickname: '鲍勃', email: null, phone: null, status: 'ENABLED', builtIn: false, roleIds: [], createdAt: '2026-01-01 10:00:00', updatedAt: '2026-01-02 11:00:00', version: '0' },
];

const makeTenant = (id: string, tenantCode: string, tenantName: string, level: number, parentId: string | null) => ({
  id, tenantCode, tenantName, status: 'ENABLED', level, parentId, packageId: null,
  version: '0', createdAt: '2026-01-01T00:00:00Z', children: [],
});

const TENANT_TREE = [
  makeTenant('9223372036854775931', 'acme', '总部', 0, null),
  makeTenant('9223372036854775933', 'standalone', '独立租户', 0, null),
];

const TENANT_ROWS = [
  makeTenant('9223372036854775931', 'acme', '总部', 0, null),
  makeTenant('9223372036854775933', 'standalone', '独立租户', 0, null),
];

const TENANT_PERMS = ['system:tenant:list', 'system:tenant:create', 'system:tenant:update', 'system:tenant-template:list', 'system:tenant-template:update'];

const tenantRoutes = (putFail: { code: string; message: string }) => ({
  // 长前缀先匹配（tenants.test 范本口径）
  '/system/tenants/tree': () => envelope(TENANT_TREE),
  '/system/tenants/init': () => envelope(TENANT_ROWS[0]),
  '/system/tenants': (r: ReturnType<typeof asRequest>) => {
    if (r.method === 'GET') return envelope({ records: TENANT_ROWS, total: TENANT_ROWS.length });
    if (r.method === 'PUT') return failureEnvelope(putFail.code, putFail.message);
    return envelope(TENANT_ROWS[0]);
  },
  '/system/tenant-packages': () => envelope({ records: [], total: 0 }),
  '/system/tenant-templates': () => envelope({ records: [], total: 0 }),
});

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— UsersPage：密码策略违反（platform-auth-02-0002）分支真命中（AC-4） —————

describe('REQ20261128051442 UsersPage 密码策略分支（AC-4）', () => {
  it('should_show_policy_violated_message_when_reset_password_hits_full_code', async () => {
    const { fetchMock } = await renderPage(UsersPage, ['system:user:list', 'auth:credential:manage'], {
      '/auth/credentials/users': (r) => (r.method === 'PUT'
        ? failureEnvelope('platform-auth-02-0002', '密码不符合策略')
        : envelope(null)),
      '/system/users': () => envelope({ records: USER_ROWS, total: USER_ROWS.length }),
    });
    const rowEl = await findRowByText('alice');
    fireEvent.click(await within(rowEl).findByText(RESET_BUTTON));

    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('新密码'), { target: { value: 'Str0ng!Pass@2026' } });
    fireEvent.click(await within(currentDialog()).findByRole('button', { name: CONFIRM_BUTTON }));

    // 分支真命中（全码 platform-auth-02-0002）：i18n 转译文案逐字出现
    expect(await screen.findByText('密码不符合策略（1~64 位非空），请调整后重试')).toBeTruthy();
    expect(callsOf(fetchMock, 'PUT', '/admin/v1/auth/credentials/users/2001/password')).toHaveLength(1);
    // 无成功提示（弹窗保持打开、输入保留——数据驱动口径）
    expect(document.body.textContent ?? '').not.toContain('密码已重置，该用户将被强制下线');
  });
});

// ————— TenantsPage：层级越界 / 层级环（platform-system-06-0004/0005）分支真命中（AC-4） —————

describe('REQ20261128051442 TenantsPage 层级分支（AC-4）', () => {
  it('should_show_depth_exceeded_message_when_tenant_update_hits_depth_full_code', async () => {
    const { fetchMock } = await renderPage(TenantsPage, TENANT_PERMS,
      tenantRoutes({ code: 'platform-system-06-0004', message: '租户层级超过上限（最多 3 层）' }));
    const row = await findRowByText('总部');
    fireEvent.click(await within(row).findByText(/^编\s*辑$/));
    fireEvent.click(await within(currentDialog()).findByRole('button', { name: SUBMIT_BUTTON }));

    expect(await screen.findByText('租户层级超过上限（最多 3 层）')).toBeTruthy();
    expect(callsOf(fetchMock, 'PUT', '/admin/v1/system/tenants/9223372036854775931')).toHaveLength(1);
  });

  it('should_show_cycle_message_when_tenant_update_hits_cycle_full_code', async () => {
    await renderPage(TenantsPage, TENANT_PERMS,
      tenantRoutes({ code: 'platform-system-06-0005', message: '父租户不能为自身或其后代（会形成层级环）' }));
    const row = await findRowByText('总部');
    fireEvent.click(await within(row).findByText(/^编\s*辑$/));
    fireEvent.click(await within(currentDialog()).findByRole('button', { name: SUBMIT_BUTTON }));

    expect(await screen.findByText('设置该父租户会形成层级环，操作已被拒绝')).toBeTruthy();
  });
});

// ————— ProfilePage：资料 / 改密三分支（platform-system-13-0001 + platform-auth-07-0001/0002）真命中（AC-4） —————

describe('REQ20261128051442 ProfilePage 资料/改密分支（AC-4）', () => {
  it('should_show_profile_invalid_message_when_me_update_hits_full_code', async () => {
    const { fetchMock } = await renderProfilePage({
      '/system/me': () => failureEnvelope('platform-system-13-0001', '资料格式不正确'),
    });
    await screen.findByLabelText('昵称');
    fireEvent.click(screen.getByRole('button', { name: '保存资料' }));

    expect(await screen.findByText('资料格式不正确（昵称必填 ≤32、邮箱格式、手机号 1 开头 11 位）')).toBeTruthy();
    await waitFor(() => {
      expect(callsOf(fetchMock, 'PUT', '/admin/v1/system/me')).toHaveLength(1);
    });
  });

  it('should_show_old_password_invalid_message_when_self_password_hits_full_code', async () => {
    const { fetchMock } = await renderProfilePage({
      '/auth/me/password': () => failureEnvelope('platform-auth-07-0001', '当前密码不正确'),
    });
    await screen.findByLabelText('昵称');
    fillPasswordForm();

    expect(await screen.findByText('当前密码不正确')).toBeTruthy();
    await waitFor(() => {
      expect(callsOf(fetchMock, 'PUT', '/admin/v1/auth/me/password')).toHaveLength(1);
    });
  });

  it('should_show_same_password_message_when_self_password_hits_same_password_full_code', async () => {
    await renderProfilePage({
      '/auth/me/password': () => failureEnvelope('platform-auth-07-0002', '新密码不能与当前密码相同'),
    });
    await screen.findByLabelText('昵称');
    fillPasswordForm();

    expect(await screen.findByText('新密码不能与当前密码相同')).toBeTruthy();
  });
});
