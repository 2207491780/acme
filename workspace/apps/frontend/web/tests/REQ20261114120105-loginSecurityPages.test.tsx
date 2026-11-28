/**
 * REQ-20261114-120105 AC-8：登录安全域前端两页 CRUD 范本回归（登录日志页 / 在线用户页）。
 *
 * 覆盖：登录日志页列表渲染（只读查询页，同 Audit 口径：无 UI 写路径；筛选形态契约未指明不假设）、
 * 在线用户页列表渲染 + 踢下线确认与结果反馈 + 权限显隐。
 * 装配照既有范本 web/tests/REQ20261114120101-crudPages.test.tsx：fetch stubGlobal 弱匹配路由 +
 * useMeStore 权限播种 + <AntApp> + testTimeout 放宽；关闭态断言一律数据驱动（071607 裁决⑪）。
 *
 * 【口径说明（契约/DTO 推导，非猜测实现；AMB 由 TL 裁决后同步调整）】
 *   - 页面为命名导出（A-35 范本口径），路径 web/src/pages/{LoginLogsPage,OnlineUsersPage}.tsx（AMB-1）。
 *   - 权限点 = V10 种子三码（auth:loginlog:list / auth:online:list / auth:online:revoke）。
 *   - DTO 形状 = 契约字段（username/userType/result/failReason/ip/…；sessionId/username/ip/登录时间/
 *     最近活跃/userType），wire 字段名未指明按 camelCase 推定（AMB-2），断言以 username 行锚定位不钉死列名。
 *   - 踢下线按钮文案推定「踢下线」、确认按钮「确定」（CrudTable 范本口径，AMB-3）。
 *   - 结果反馈（AC-8「踢下线有确认与结果反馈」）：提交成功后断言全局 message 含「下线/成功」字样
 *     （文案 AMB-3）+ 成功后 reload 二次 GET（071607 裁决⑪ 数据驱动）。
 *   - AC-4 前端「401 清会话跳登录」为 core/App 层行为，非两页 CRUD 范本可覆盖 → 未决上报 TL。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { LoginLogsPage } from '../src/pages/LoginLogsPage';
import { OnlineUsersPage } from '../src/pages/OnlineUsersPage';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 25000 });

// ————— 行为常量（crudPages 范本口径） —————
const CONFIRM_BUTTON = /确\s*定|^OK$/i;
const KICK_BUTTON = /踢\s*下\s*线/;

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

// ————— fetch 边界 mock（照抄范本 helpers） —————
type FetchArgs = [input: RequestInfo | URL, init?: RequestInit];

const asRequest = (input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' || input instanceof URL) {
    return { url: new URL(String(input), 'http://localhost'), method: (init?.method ?? 'GET').toUpperCase(), body: init?.body };
  }
  const req = input as Request;
  return { url: new URL(req.url), method: (init?.method ?? req.method).toUpperCase(), body: init?.body };
};

const callsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string, pathPart: string) =>
  (fetchMock.mock.calls as unknown as FetchArgs[])
    .map(([input, init]) => asRequest(input, init))
    .filter((r) => r.url.pathname.includes(pathPart) && r.method === method);

const lastQuery = (fetchMock: ReturnType<typeof vi.fn>, pathPart: string) =>
  callsOf(fetchMock, 'GET', pathPart).at(-1)?.url.searchParams ?? null;

// ————— 页面描述符 —————
type PageSpec = {
  key: string;
  Component: React.ComponentType;
  pathPart: string;
  /** 契约 §API：true=分页端点（Result<{records,total}>），false=非分页列表（Result<List>） */
  paged: boolean;
  rows: Record<string, unknown>[];
  anchors: string[];
};

const LOGIN_LOG_ROWS: Record<string, unknown>[] = [
  {
    id: '6001', username: 'alice', userType: 'B', ip: '10.0.0.1', userAgent: 'ua-alice',
    result: 'SUCCESS', failReason: null, traceId: 'trace-1', createdAt: '2026-11-14T08:00:00Z',
  },
  {
    id: '6002', username: 'bob', userType: 'C', ip: '10.0.0.2', userAgent: 'ua-bob',
    result: 'FAIL', failReason: '密码错误', traceId: 'trace-2', createdAt: '2026-11-14T09:00:00Z',
  },
];

const ONLINE_USER_ROWS: Record<string, unknown>[] = [
  {
    sessionId: 'sess-1', username: 'alice', ip: '10.0.0.1', userType: 'B',
    loginAt: '2026-11-14T08:00:00Z', lastActiveAt: '2026-11-14T08:30:00Z',
  },
  {
    sessionId: 'sess-2', username: 'bob', ip: '10.0.0.2', userType: 'B',
    loginAt: '2026-11-14T07:00:00Z', lastActiveAt: '2026-11-14T07:40:00Z',
  },
];

const SPECS: PageSpec[] = [
  {
    key: 'LoginLogs',
    Component: LoginLogsPage,
    pathPart: 'login-logs',
    // 契约 §API：GET /admin/v1/auth/login-logs = 分页（username/userType/result/时间区间筛选）
    paged: true,
    rows: LOGIN_LOG_ROWS,
    anchors: ['alice', 'bob'],
  },
  {
    key: 'OnlineUsers',
    Component: OnlineUsersPage,
    pathPart: 'online-users',
    // 契约 §API：GET /admin/v1/auth/online-users = 活跃会话列表（非分页）
    paged: false,
    rows: ONLINE_USER_ROWS,
    anchors: ['alice', 'bob'],
  },
];

const setupApi = (spec: PageSpec) => {
  const state = { rows: spec.rows.map((r) => ({ ...r })) };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const r = asRequest(input, init);
    const p = r.url.pathname;
    if (!p.includes(spec.pathPart)) return envelope(null);
    if (r.method === 'GET') {
      // 分页页=Result<{records,total}>（CrudTable 口径）；列表页=Result<List>（契约 §API 非分页）
      return spec.paged
        ? envelope({ records: state.rows, total: state.rows.length })
        : envelope(state.rows);
    }
    if (r.method === 'DELETE') {
      const id = p.split('/').filter(Boolean).pop() ?? '';
      state.rows = state.rows.filter(
        (item) => item.id !== id && item.sessionId !== id,
      );
      return envelope(null); // 幂等成功（范本口径）
    }
    return envelope(null);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
};

// ————— 装配（照抄范本） —————
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

const renderPage = async (spec: PageSpec, permissions: string[]) => {
  await seedMe(permissions);
  const api = setupApi(spec);
  render(<AntApp><spec.Component /></AntApp>);
  return api;
};

const findByAnyText = async (candidates: string[]) =>
  waitFor(
    () => {
      for (const c of candidates) {
        const el = screen.queryByText(c);
        if (el) return el;
      }
      throw new Error(`未找到任何行锚文本: ${candidates.join(' / ')}`);
    },
    { timeout: 3000 },
  );

const findRowByText = async (anchor: string) => {
  const cell = await screen.findByText(anchor);
  return cell.closest('tr') as HTMLElement;
};

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
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

// ————— 用例：每页断言 AC-8 可适用子集 —————
const listPermOf = (spec: PageSpec): string[] =>
  spec.key === 'LoginLogs' ? ['auth:loginlog:list'] : ['auth:online:list'];

for (const spec of SPECS) {
  const listPerms = listPermOf(spec);

  describe(`REQ20261114120105 ${spec.key} CRUD 范本回归（AC-8）`, () => {
    it(`should_render_list_rows_when_${spec.key}_loads`, async () => {
      const { fetchMock } = await renderPage(spec, listPerms);
      await findByAnyText(spec.anchors);
      if (spec.paged) {
        // 分页页列表请求携带 page=1（A-33 范本口径）；列表页（契约非分页）不断言分页参数
        expect(lastQuery(fetchMock, spec.pathPart)?.get('page')).toBe('1');
      }
      expect(callsOf(fetchMock, 'GET', spec.pathPart).length).toBeGreaterThanOrEqual(1);
    });

    it(`should_hide_unauthorized_actions_when_${spec.key}_list_permission_missing`, async () => {
      // 范本权限显隐口径（crudPages）：持 list 权限渲染（权限门禁在服务端），断言写路径动作隐藏
      await renderPage(spec, listPerms);
      await findByAnyText(spec.anchors);
      expect(screen.queryByText(/新\s*[建增]/)).toBeNull();
      expect(screen.queryByText(/删\s*除/)).toBeNull();
      expect(screen.queryByText(KICK_BUTTON)).toBeNull();
    });
  });
}

describe('REQ20261114120105 OnlineUsers 踢下线交互（AC-8 / AC-4 前端触发面）', () => {
  it('should_confirm_and_revoke_session_with_result_feedback_when_kick_clicked', async () => {
    const spec = SPECS[1];
    const { fetchMock } = await renderPage(spec, ['auth:online:list', 'auth:online:revoke']);
    const rowEl = await findRowByText('bob');

    fireEvent.click(await within(rowEl).findByText(KICK_BUTTON));
    // 踢下线须有确认（AC-8）：确认弹窗出现后才发起请求
    expect(callsOf(fetchMock, 'DELETE', spec.pathPart)).toHaveLength(0);
    fireEvent.click(await screen.findByRole('button', { name: CONFIRM_BUTTON }));

    await waitFor(() => {
      const deletes = callsOf(fetchMock, 'DELETE', spec.pathPart);
      expect(deletes).toHaveLength(1);
      // DELETE /admin/v1/auth/online-users/{sessionId}（契约端点，路径参数=sessionId）
      expect(deletes[0]?.url.pathname).toContain('sess-2');
    });
    // 结果反馈（AC-8）：全局 message 含「下线/成功」字样（文案 AMB-3）
    await waitFor(() => {
      const feedback = document.body.textContent ?? '';
      expect(feedback).toMatch(/下线|成功/);
    });
    // 关闭态数据驱动：成功后 reload 二次 GET（071607 裁决⑪）
    await waitFor(() => {
      expect(callsOf(fetchMock, 'GET', spec.pathPart).length).toBeGreaterThanOrEqual(2);
    });
    await waitFor(() => expect(screen.queryByText('bob')).toBeNull());
    expect(screen.getByText('alice')).toBeTruthy();
  });

  it('should_hide_revoke_action_when_online_revoke_permission_missing', async () => {
    const spec = SPECS[1];
    await renderPage(spec, ['auth:online:list']); // 仅 list 权限
    await findByAnyText(spec.anchors);
    const rowEl = await findRowByText('bob');
    expect(within(rowEl).queryByText(KICK_BUTTON)).toBeNull();
  });
});

describe('REQ20261114120105 401 清会话跳登录（AC-4 前端半句，TL 裁决 3② 补最小用例）', () => {
  // App 整棵装配需桌面布局（REQ20261114120103 route 用例口径：min-width 断点置 true）
  const stubDesktopGaps = () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('min-width'), media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(),
      addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }));
  };

  const meUser = {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  };

  it('should_clear_session_and_redirect_to_login_when_api_returns_401', async () => {
    stubDesktopGaps();
    // 播种既有会话（REQ20261114120103 seedSession 口径）
    useAuthStore.getState().setSession({
      accessToken: 'token-1',
      expiresAtMs: Date.now() + 1_800_000,
      userId: '1001',
      username: 'admin',
      nickname: null,
      tenantCode: 'default',
    }, false);
    // App 整棵装配：/me 精确收敛 + 租户页请求返回 401（被踢会话语义的客户端可观测面）
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const r = asRequest(input, init);
      if (r.method === 'GET' && r.url.pathname.endsWith('/me')) {
        return envelope({ user: meUser, roles: ['super_admin'], permissions: ['system:tenant:list'], menus: [] });
      }
      if (r.url.pathname.includes('/system/tenants')) {
        return new Response(
          JSON.stringify({ success: false, code: '401', message: 'unauthorized' }), { status: 401 },
        );
      }
      return envelope(null);
    }));
    await useMeStore.getState().load();
    window.location.hash = '#/tenants';
    render(<AntApp><HashRouter><App /></HashRouter></AntApp>);

    await waitFor(() => {
      // 跳登录：hash 路由 #/login（frontend 交付报告确认，AMB-4 关闭）
      expect(window.location.hash).toBe('#/login');
    });
    await waitFor(() => {
      // 清会话（形状无关断言）：auth store 状态中不再携带被踢令牌
      expect(JSON.stringify(useAuthStore.getState())).not.toContain('token-1');
    });
  });
});
