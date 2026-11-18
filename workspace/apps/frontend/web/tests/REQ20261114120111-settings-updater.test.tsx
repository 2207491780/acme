/**
 * REQ-20261114-120111 AC-1/AC-2（UI 行为）：设置页「检查更新」小节三态提示。
 *
 * mock 桌面桥接 window.acmeDesktop（checkUpdates/getInfo），覆盖：
 *   - 有新版本（ready）→ 提示新版本就绪 + 出现「重启并安装」按钮（AC-1 UI 面）；
 *   - 无新版本（up-to-date）→ 提示已是最新（AC-2 UI 面）；
 *   - 更新源不可达（unreachable）→ 明确提示（AC-2 UI 面）。
 * 附加覆盖：非桌面环境（无桥接）小节隐藏（优雅降级）。
 *
 * 装配照既有范本 web/tests/REQ20261114120103-i18nPages.test.tsx：
 * I18nextProvider(core i18n 单例) + <AntApp> 包裹 + matchMedia/ResizeObserver 桩 + act 环境。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act } from 'react';
import type { ComponentProps, ReactNode } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { I18nextProvider } from 'react-i18next';
import { Settings } from '../src/pages/Settings';
import * as coreModule from '@acme/core';

vi.setConfig({ testTimeout: 15000 });

// React 18 act 环境（i18nPages 范本口径）
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// ————— 桌面桥接 mock（表面 = web/src/global.d.ts AcmeDesktopBridge） —————

type CheckResult = Awaited<ReturnType<NonNullable<AcmeDesktopBridge['checkUpdates']>>>;

type BridgeMock = {
  platform: string;
  versions: { electron: string; chrome: string };
  getInfo: ReturnType<typeof vi.fn>;
  checkUpdates: ReturnType<typeof vi.fn>;
  installUpdates: ReturnType<typeof vi.fn>;
};

let bridge: BridgeMock;

function setupBridge() {
  bridge = {
    // bridge 静态面（platform/versions）需完整：Settings 顶部 runtime 行同步读取
    platform: 'win32',
    versions: { electron: '37.0.0', chrome: 'test-chrome' },
    getInfo: vi.fn(async () => ({
      platform: 'win32',
      version: '1.0.0',
      versions: { electron: '37.0.0', chrome: 'test-chrome' },
    })),
    checkUpdates: vi.fn(async (): Promise<CheckResult> => ({ status: 'up-to-date' })),
    installUpdates: vi.fn(async () => ({ ok: true })),
  };
  window.acmeDesktop = bridge as unknown as AcmeDesktopBridge;
}

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

// ————— 渲染壳（i18nPages 口径：core i18n 实例作 Provider） —————

type I18nLike = {
  language?: string;
  changeLanguage: (lng: string) => Promise<unknown> | void;
  t: (key: string, opts?: unknown) => string;
};

const isI18nLike = (v: unknown): v is I18nLike =>
  !!v && typeof v === 'object'
  && typeof (v as I18nLike).changeLanguage === 'function'
  && typeof (v as I18nLike).t === 'function';

let cachedI18n: I18nLike | null = null;

const coreI18n = (): I18nLike => {
  if (cachedI18n) return cachedI18n;
  const mod = coreModule as unknown as Record<string, unknown>;
  for (const value of Object.values(mod)) {
    if (isI18nLike(value)) {
      cachedI18n = value;
      return value;
    }
  }
  throw new Error('未在 @acme/core 导出面发现 i18next 实例');
};

type ProviderI18n = ComponentProps<typeof I18nextProvider>['i18n'];

const RenderShell = ({ children }: { children: ReactNode }) => (
  <I18nextProvider i18n={coreI18n() as unknown as ProviderI18n}>
    <AntApp>{children}</AntApp>
  </I18nextProvider>
);

const renderSettings = async () => {
  render(<RenderShell><Settings /></RenderShell>);
  // 「检查更新」同时出现在卡片标题与按钮上 → 以 role 定位；按钮名含图标 aria-label，用正则匹配
  await waitFor(() => expect(screen.getByRole('button', { name: /检查更新/ })).toBeTruthy());
};

const clickCheck = async () => {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: /检查更新/ }));
  });
};

beforeEach(() => {
  window.localStorage.clear();
  stubBrowserGaps();
});

afterEach(() => {
  cleanup();
  delete window.acmeDesktop;
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('REQ20261114120111 设置页检查更新（AC-1/AC-2 UI）', () => {
  it('should_show_current_version_and_update_section_when_desktop_bridge_present', async () => {
    setupBridge();
    await renderSettings();
    // 当前版本展示（getInfo 直通）+ 手动检查按钮
    await waitFor(() => expect(screen.getByText('1.0.0')).toBeTruthy());
    expect(screen.getByRole('button', { name: /检查更新/ })).toBeTruthy();
    expect(bridge.getInfo).toHaveBeenCalled();
  });

  it('should_show_ready_and_install_button_when_new_version_downloaded', async () => {
    setupBridge();
    bridge.checkUpdates.mockResolvedValue({ status: 'ready', version: '9.9.9' });
    await renderSettings();
    await clickCheck();
    // 有新版本提示（含版本号）+ 「重启并安装」出现（AC-1）
    await waitFor(() => expect(screen.getByText(/9\.9\.9/)).toBeTruthy());
    expect(screen.getByText(/发现新版本 9\.9\.9，下载完成，可重启安装/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /重启并安装/ })).toBeTruthy();
    expect(bridge.checkUpdates).toHaveBeenCalledTimes(1);
  });

  it('should_show_up_to_date_when_no_new_version', async () => {
    setupBridge();
    await renderSettings();
    await clickCheck();
    await waitFor(() => expect(screen.getByText('已是最新版本')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /重启并安装/ })).toBeNull();
  });

  it('should_show_unreachable_when_update_source_fails', async () => {
    setupBridge();
    bridge.checkUpdates.mockResolvedValue({ status: 'unreachable', error: 'net::ERR_NAME_NOT_RESOLVED' });
    await renderSettings();
    await clickCheck();
    await waitFor(() => expect(screen.getByText('更新源不可达，请稍后重试')).toBeTruthy());
  });

  it('should_show_unreachable_when_check_updates_rejects', async () => {
    setupBridge();
    bridge.checkUpdates.mockRejectedValue(new Error('bridge failure'));
    await renderSettings();
    await clickCheck();
    await waitFor(() => expect(screen.getByText('更新源不可达，请稍后重试')).toBeTruthy());
  });

  it('should_install_updates_when_install_button_clicked_after_ready', async () => {
    setupBridge();
    bridge.checkUpdates.mockResolvedValue({ status: 'ready', version: '9.9.9' });
    await renderSettings();
    await clickCheck();
    await screen.findByRole('button', { name: /重启并安装/ });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /重启并安装/ }));
    });
    expect(bridge.installUpdates).toHaveBeenCalledTimes(1);
  });

  it('should_hide_update_section_without_desktop_bridge', async () => {
    delete window.acmeDesktop;
    render(<RenderShell><Settings /></RenderShell>);
    await screen.findByText('数据管理'); // 页面本体仍渲染
    expect(screen.queryByRole('button', { name: /检查更新/ })).toBeNull();
  });
});
