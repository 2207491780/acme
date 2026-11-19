/**
 * 缺陷回归：切换时区后页面时间列不更新。
 *
 * 根因：页面 element 是 routes.tsx 的模块级静态实例，父级重渲染（含时区切换触发的 App 重渲染、
 * 既有 1.2s TICK）被 React 元素同引用 bail-out 屏蔽，页面子树不重渲染；而 formatDate 系
 * formatter 读 date-format 模块级全局缺省（setTimezone 联动），页面缺订阅 → 时间列维持旧渲染。
 *
 * 修复口径（本测试锁定的契约）：保活容器内页面 element 必须经「全局日期偏好重渲染门」包裹——
 * 时区/模板/语言任一偏好变化时以新元素引用强制页面重渲染（渲染期 formatter 重读缺省即得新值）；
 * 偏好未变时保持引用（保活语义不破坏：切签/父级例行重渲染不带动页面）。
 *
 * 装配照 loggingJobs/nav.test 范本口径：整棵 App（HashRouter + AntApp）+ 会话播种 + me 授
 * auth:loginlog:list + /auth/login-logs 单端点 stub（绝对时间列 render formatDate，无本地订阅）。
 * 冻结 Date（仅 Date）：createdAt 固定瞬时跨时区换算确定，宿主时钟不参与断言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import {
  useDateFormatStore,
  useAuthStore,
  useMeStore,
  useTimezoneStore,
} from '@acme/core';
import { App } from '../src/App';

vi.setConfig({ testTimeout: 20000 });

const ROW_INSTANT = '2026-11-17T00:30:00Z'; // 上海（UTC+8）= 11-17 08:30；纽约（UTC-5）= 11-16 19:30

/** 登录日志行（AdminLoginLogView wire 形状，绝对时间列 render formatDate，无本地订阅）。 */
const row = {
  id: '9001', username: 'admin', userType: 'ADMIN', ip: '10.0.0.1', userAgent: 'vitest',
  result: 'SUCCESS', failReason: null, traceId: 'tz-1', createdAt: ROW_INSTANT,
};

const envelope = (data: unknown) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status: 200 },
);

const stubBrowserGaps = () => {
  // 断点全不匹配会把 App 压成移动布局（Sider/Menu 不挂载）——min-width 置 true 按桌面布局渲染
  // （nav.test 范本 A-24 口径）
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

const renderAppWithRow = async () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1', expiresAtMs: Date.now() + 1_800_000,
    userId: '1001', username: 'admin', nickname: null, tenantCode: 'default',
  }, false);
  stubBrowserGaps();
  window.location.hash = '#/login-logs';
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = typeof input === 'string' || input instanceof URL
      ? new URL(String(input), 'http://localhost')
      : new URL((input as Request).url);
    if (url.pathname.includes('/system/me')) {
      return envelope({
        user: {
          id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
          status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
        },
        roles: ['super_admin'],
        permissions: ['auth:loginlog:list'],
        menus: [],
      });
    }
    if (url.pathname.includes('/auth/login-logs')) {
      return envelope({ records: [row], total: 1 });
    }
    return envelope(null);
  }));
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  // 仅冻结 Date：绝对时间列断言不依赖宿主时钟；waitFor（真实定时器）不受影响
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-17T12:00:00Z'));
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  useTimezoneStore.getState().setTimezone('system');
  useDateFormatStore.getState().setTemplate('auto');
  window.localStorage.clear();
  window.sessionStorage.clear();
  window.location.hash = '';
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
});

describe('时区切换后页面时间列更新（缺陷回归）', () => {
  it('should_refresh_loginlog_time_cell_when_timezone_switched', async () => {
    // 缺省时区锚定为上海：断言起点确定（system 哨兵随宿主时区漂移，不可作断言起点）
    act(() => {
      useTimezoneStore.getState().setTimezone('Asia/Shanghai');
    });
    await renderAppWithRow();

    // 页面就绪锚：上海时区下的绝对时间列
    expect(await screen.findByText('2026-11-17 08:30:00', {}, { timeout: 5000 })).toBeTruthy();

    // 切换时区 → 同一单元格立即换算为纽约时间（缺陷态：旧值保持不变）
    act(() => {
      useTimezoneStore.getState().setTimezone('America/New_York');
    });
    expect(await screen.findByText('2026-11-16 19:30:00', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.queryByText('2026-11-17 08:30:00')).toBeNull();
  });

  it('should_refresh_loginlog_time_cell_when_date_template_switched', async () => {
    // 同根缺陷第二面：日期模板偏好切换同样必须穿透保活容器
    act(() => {
      useTimezoneStore.getState().setTimezone('Asia/Shanghai');
    });
    await renderAppWithRow();
    expect(await screen.findByText('2026-11-17 08:30:00', {}, { timeout: 5000 })).toBeTruthy();

    act(() => {
      useDateFormatStore.getState().setTemplate('YYYY/MM/DD HH:mm');
    });
    expect(await screen.findByText('2026/11/17 08:30', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.queryByText('2026-11-17 08:30:00')).toBeNull();
  });
});
