/**
 * 日期国际化·时区维度（web 侧）：设置页时区选择联动全站 formatter，
 * 日期模板独立于语言/时区；发布单数据源趋势用例随 REQ-20261126-125836 删除。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { formatDate, seedState, useDateFormatStore, useLocaleStore, useTimezoneStore } from '@acme/core';
import { useStore } from '../src/store/useStore';
import { Settings } from '../src/pages/Settings';

beforeEach(() => {
  // antd 响应式断点依赖 matchMedia/ResizeObserver（jsdom 未实现，nav.test 同口径装配桩）
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
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  useTimezoneStore.getState().setTimezone('system');
  useDateFormatStore.getState().setTemplate('auto');
  useStore.setState({ state: seedState() });
  window.localStorage.clear();
});

describe('设置页时区选择', () => {
  it('should_link_store_and_formatter_when_timezone_selected', async () => {
    render(<AntApp><Settings /></AntApp>);
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '展示时区' }));
    fireEvent.click(await screen.findByText('上海 (UTC+08:00)', {}, { timeout: 5000 }));
    expect(useTimezoneStore.getState().timezone).toBe('Asia/Shanghai');
    expect(formatDate('2026-11-14T03:05:09Z')).toBe('2026-11-14 11:05:09');
    expect(window.localStorage.getItem('acme.timezone.v1')).toContain('Asia/Shanghai');
  });
});

describe('设置页日期格式模板选择（独立于语言/时区）', () => {
  it('should_link_store_and_formatter_when_template_selected', async () => {
    render(<AntApp><Settings /></AntApp>);
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '日期时间模板' }));
    fireEvent.click(await screen.findByText('YYYY/MM/DD HH:mm', {}, { timeout: 5000 }));
    expect(useDateFormatStore.getState().template).toBe('YYYY/MM/DD HH:mm');
    // 模板生效（时区仍跟随系统 → 以本地墙钟推算期望值，避免环境时区漂移）
    const d = new Date('2026-11-14T03:05:09Z');
    const pad = (n: number) => String(n).padStart(2, '0');
    expect(formatDate('2026-11-14T03:05:09Z')).toBe(
      `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`,
    );
    expect(window.localStorage.getItem('acme.dateformat.v1')).toContain('YYYY/MM/DD HH:mm');
    // 语言/时区偏好不受模板选择影响（三者独立）
    expect(useTimezoneStore.getState().timezone).toBe('system');
    expect(useLocaleStore.getState().locale).toBe('zh-CN');
  });
});