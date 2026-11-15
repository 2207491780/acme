/**
 * REQ-20261114-120112 AC-4（web 层）：锁屏遮罩组件——全屏遮罩、口令解锁成功/失败提示、
 * 忘记口令→重新登录路径。
 *
 * 装配口径：组件级直接渲染（自读 core useLockStore/useAuthStore，参照 errorBoundary 范本
 * 的 <AntApp> 包裹口径；HashRouter 承接「忘记口令→重新登录」的路由跳转断言）。
 *
 * 【表面假定（REQ20261114120112 套件内连续编号）】
 *   A-16 web/src/components/LockScreenOverlay 命名导出 LockScreenOverlay（components/
 *        NotificationBell 命名导出惯例）；locked=false 时渲染 null（遮罩仅锁屏态在场）。
 *   A-17 遮罩根节点 inline style position:fixed 覆盖内容区（契约「全屏遮罩」）。
 *   A-18 遮罩含 input[type=password] 与「解锁」按钮（文案未契约化 → 正则 /解\s*锁/，
 *        兼容 antd Button 两字中文文案自动插空格）；
 *        失败提示经 AntApp message 呈现，文案未契约化 → 正则 /错误|不正确|失败/，
 *        并断言 locked 保持 true。
 *   A-19 「忘记口令」入口（文案正则 /忘记/）点击后：清除会话（useAuthStore）并跳转
 *        '#/login'（hash 路由；登录路径未契约化 → TL 门禁对齐）。
 *   A-20 复用 core 侧 A-5/A-6 的 useLockStore 表面（setPasscode/lock/unlock）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { HashRouter } from 'react-router-dom';
import { LockScreenOverlay } from '../src/components/LockScreenOverlay';
import { useAuthStore, useLockStore } from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

type LockApi = {
  locked: boolean;
  lock: () => void;
  setPasscode: (code: string) => void;
};

const lockApi = (): LockApi => useLockStore.getState() as unknown as LockApi;
const isLocked = () => Boolean(lockApi().locked);

const seedSession = () => {
  useAuthStore.getState().setSession({
    accessToken: 'token-1',
    expiresAtMs: Date.now() + 1_800_000,
    userId: 1001,
    username: 'admin',
    nickname: null,
    tenantCode: 'default',
  }, false);
};

/** 会话在否（A-19：会话字段形状未契约化 → 常见字段探测）。 */
const hasSession = () => {
  const s = useAuthStore.getState() as unknown as Record<string, unknown>;
  return Boolean(s.session ?? s.accessToken ?? s.token);
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

const renderOverlay = () => {
  render(<AntApp><HashRouter><LockScreenOverlay /></HashRouter></AntApp>);
};

/** seed + 设置口令 + 手动上锁（A-20）。 */
const seedAndLock = (code = '4242') => {
  seedSession();
  act(() => {
    lockApi().setPasscode(code);
    lockApi().lock();
  });
};

const typePasscode = (container: HTMLElement, value: string) => {
  const input = container.querySelector('input[type="password"]');
  expect(input, 'A-18：遮罩未提供 password 输入').not.toBeNull();
  fireEvent.change(input as HTMLElement, { target: { value } });
};

beforeEach(() => {
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  (useLockStore.setState as unknown as (partial: Record<string, unknown>) => void)({ locked: false });
  stubBrowserGaps();
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.location.hash = '';
  window.localStorage.clear();
  window.sessionStorage.clear();
  useAuthStore.getState().clear();
  (useLockStore.setState as unknown as (partial: Record<string, unknown>) => void)({ locked: false });
});

// ————— AC-4 —————
describe('REQ20261114120112 锁屏遮罩（AC-4）', () => {
  it('should_render_fixed_mask_when_locked', () => {
    seedAndLock();
    renderOverlay();
    const mask = Array.from(document.body.querySelectorAll('div')).find(
      (d) => d.style.position === 'fixed',
    );
    expect(mask, 'A-17：锁屏态未渲染 position:fixed 全屏遮罩').not.toBeNull();
  });

  it('should_unlock_and_remove_mask_when_passcode_correct', async () => {
    seedAndLock('4242');
    const { container } = render(<AntApp><HashRouter><LockScreenOverlay /></HashRouter></AntApp>);
    typePasscode(container, '4242');
    fireEvent.click(screen.getByRole('button', { name: /解\s*锁/ }));
    await waitFor(() => expect(isLocked()).toBe(false));
    await waitFor(() => {
      expect(container.querySelector('input[type="password"]')).toBeNull(); // 遮罩移除（A-16）
    });
  });

  it('should_show_error_and_stay_locked_when_passcode_wrong', async () => {
    seedAndLock('4242');
    const { container } = render(<AntApp><HashRouter><LockScreenOverlay /></HashRouter></AntApp>);
    typePasscode(container, '0000');
    fireEvent.click(screen.getByRole('button', { name: /解\s*锁/ }));
    const hint = await screen.findByText(/错误|不正确|失败/); // 口令错误提示（A-18）
    expect(hint).not.toBeNull();
    expect(isLocked()).toBe(true); // 失败不解锁
  });

  it('should_clear_session_and_redirect_login_when_forgot_clicked', async () => {
    seedAndLock('4242');
    expect(hasSession()).toBe(true);
    render(<AntApp><HashRouter><LockScreenOverlay /></HashRouter></AntApp>);
    fireEvent.click(screen.getByText(/忘记/)); // 忘记口令入口（A-19）
    await waitFor(() => expect(window.location.hash).toBe('#/login')); // 重新登录路径
    await waitFor(() => expect(hasSession()).toBe(false)); // 会话清除
  });
});
