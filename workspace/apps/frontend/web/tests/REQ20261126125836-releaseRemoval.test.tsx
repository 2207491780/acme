/**
 * REQ-20261126-125836「删除多端发布演示功能」web 侧契约用例（AC-4 / AC-6 UI 面）。
 *
 * 表面 = 契约 §验收标准 + 确认记录⑦：
 *   - /releases 路由与发布单入口、新建发布单按钮、Header 搜索框/Ctrl+K 徽标均不存在；
 *   - 顶栏偏好含 legacy release 键时该键被接受但忽略，UI 不渲染新建发布单按钮。
 * 装配照 web/tests/REQ20261119063129-tabs.test.tsx（会话/me 播种 + HashRouter + AntApp + 浏览器桩）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { App } from '../src/App';
import { useStore } from '../src/store/useStore';
import {
  DEFAULT_PREFERENCES,
  useAuthStore,
  useMeStore,
  type ThemePreferences,
} from '@acme/core';

vi.setConfig({ testTimeout: 30000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

const mePayload = {
  user: {
    id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
    status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
  },
  roles: ['super_admin'], permissions: [], menus: [],
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

const renderApp = () => {
  render(<AntApp><HashRouter><App /></HashRouter></AntApp>);
};

beforeEach(() => {
  window.location.hash = '#/';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  useMeStore.getState().clear();
  useStore.getState().dispatch({ type: 'UPDATE_SETTINGS', patch: { preferences: { ...DEFAULT_PREFERENCES } } });
  stubBrowserGaps();
  vi.stubGlobal('fetch', vi.fn(async () => envelope(null)));
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

describe('REQ20261126125836 发布入口删除（AC-4）', () => {
  it('should_have_no_release_entry_when_web_app_renders', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'token-1', expiresAtMs: Date.now() + 1_800_000,
      userId: '1001', username: 'admin', nickname: null, tenantCode: 'default',
    }, false);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/me')) return envelope(mePayload);
      return envelope(null);
    }));
    await useMeStore.getState().load();
    renderApp();

    await waitFor(() => expect(screen.getByText('平台概览')).toBeTruthy());
    // 导航/页签/命令入口均无发布单；无新建发布单按钮；Header 不再渲染发布搜索框
    expect(screen.queryByText('发布单')).toBeNull();
    expect(screen.queryByRole('button', { name: '新建发布单' })).toBeNull();
    expect(screen.queryByPlaceholderText(/发布单/)).toBeNull();
    expect(document.querySelector('.tabbar-tab[data-tab-path="/releases"]')).toBeNull();
    expect(document.querySelector('a[href="#/releases"]')).toBeNull();

    // 直接访问旧路由不渲染发布页
    window.location.hash = '#/releases';
    await waitFor(() => expect(screen.queryByText('发布单')).toBeNull());
    expect(screen.queryByRole('button', { name: '新建发布单' })).toBeNull();
  });
});

describe('REQ20261126125836 legacy release widget（AC-6 UI 面）', () => {
  it('should_ignore_legacy_release_widget_when_preferences_loaded', async () => {
    const legacyPrefs = {
      ...DEFAULT_PREFERENCES,
      topbarWidgets: { ...DEFAULT_PREFERENCES.topbarWidgets, release: true },
    } as unknown as ThemePreferences;
    const ok = useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: legacyPrefs },
    });
    expect(ok).toBe(true);
    const prefs = (useStore.getState().state.settings as unknown as { preferences: Record<string, unknown> }).preferences;
    expect('release' in (prefs['topbarWidgets'] as object)).toBe(false);
    expect(prefs['topbarWidgets']).toEqual(DEFAULT_PREFERENCES.topbarWidgets);

    renderApp();
    await waitFor(() => expect(screen.queryByRole('button', { name: '新建发布单' })).toBeNull());
  });
});
