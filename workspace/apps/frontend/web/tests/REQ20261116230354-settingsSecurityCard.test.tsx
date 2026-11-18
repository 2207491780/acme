/**
 * REQ-20261116-230354 AC-7/AC-8（设置页负向断言）：SecurityCard 移除水印开关与闲置时长选择，
 * 口令设置保留（契约确认记录①②：锁屏口令属个人本地，保留；两项租户策略强制生效，个人设置项移除）。
 *
 * 装配照范本 REQ20261114120101-crudPages.test.tsx（AntApp + fetch 桩 + me 播种 + 浏览器桩）。
 *
 * 装配假定（契约未钉死设置页导出与卡片文案，TL 执行时按实际校准）：
 *   - 设置页命名导出 SettingsPage（路径惯例 web/src/pages/<Key>Page.tsx，同范本 A-35）——AMB-UI-3；
 *   - 安全卡标题含「安全」，卡片容器 .ant-card；负向断言限定安全卡内（设置页另有语言/时区等
 *     独立卡片含 Select，不可全页断言 combobox 为空）——AMB-UI-4。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { Settings as SettingsPage } from '../src/pages/Settings';
import { useAuthStore, useMeStore } from '@acme/core';

vi.setConfig({ testTimeout: 20000 });

const envelope = (data: unknown, status = 200) => new Response(
  JSON.stringify({ success: true, code: '0', message: null, data }), { status },
);

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

const seedMe = async () => {
  vi.stubGlobal('fetch', vi.fn(async () => envelope({
    user: {
      id: '1001', username: 'admin', nickname: '超级管理员', email: null, phone: null,
      status: 'ENABLED', builtIn: true, roleIds: [], createdAt: '', updatedAt: '', version: '0',
    },
    roles: ['super_admin'],
    permissions: ['system:user:list'],
    menus: [],
  })));
  await useMeStore.getState().load();
};

/** 安全卡容器（标题含「安全」→ 上溯 .ant-card，AMB-UI-4）。 */
const securityCard = async () => {
  const title = await screen.findByText(/安\s*全/);
  const card = title.closest('.ant-card');
  expect(card, '安全卡应为 .ant-card 容器').toBeTruthy();
  return card as HTMLElement;
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

describe('REQ20261116230354 AC-7/AC-8 设置页安全卡负向断言', () => {
  it('should_removeWatermarkSwitch_when_settingsSecurityCardRendered', async () => {
    await seedMe();
    render(<AntApp><SettingsPage /></AntApp>);
    const card = await securityCard();
    // 水印开关已移除（契约变更清单：删除 SecurityCard 水印开关行）
    expect(within(card).queryByRole('switch')).toBeNull();
    expect(within(card).queryByText(/水\s*印/)).toBeNull();
  });

  it('should_removeIdleSelectAndKeepPasscode_when_settingsSecurityCardRendered', async () => {
    await seedMe();
    render(<AntApp><SettingsPage /></AntApp>);
    const card = await securityCard();
    // 闲置时长选择已移除（契约变更清单：删除 SecurityCard 时长行）
    expect(within(card).queryByRole('combobox')).toBeNull();
    expect(within(card).queryByText(/闲置|锁屏时长/)).toBeNull();
    // 口令设置保留（契约确认记录①：个人本地口令不属租户策略）
    expect(within(card).getAllByText(/口\s*令/).length).toBeGreaterThan(0);
  });
});