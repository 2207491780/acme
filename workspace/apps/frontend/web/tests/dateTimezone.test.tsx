/**
 * 日期国际化·时区维度（web 侧）：Dashboard 趋势图按生效时区分桶，
 * 时区切换即时重算（useTimezoneStore 订阅）；设置页时区选择联动全站 formatter。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { formatDate, seedState, useDateFormatStore, useLocaleStore, useTimezoneStore, type Release } from '@acme/core';
import { useDashboardCharts } from '../src/hooks/useDashboardCharts';
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

/**
 * 「上海今天＋纽约昨天」的单解窗口依赖当前时刻：仅当「现在」落在当日 UTC 05:00–16:00
 * （上海当日 13:00 起至次日 00:00 前）前提才成立，按真实时钟锚定会在其余窗口恒红。
 * 故冻结「现在」到固定瞬时（12:00 UTC = 上海 20:00 / 纽约 07:00，同日），前提在任何宿主时间恒成立。
 */
const FROZEN_NOW = '2026-11-17T12:00:00Z';
/** 冻结时刻当日 00:30 UTC 的瞬时：上海（UTC+8）落「今天」，纽约（UTC-5）落「昨天」。 */
const BOUNDARY_INSTANT = '2026-11-17T00:30:00Z';

const seedSingleRelease = (updatedAt: string) => {
  const base = seedState();
  const rel: Release = { ...base.releases[0]!, id: 'tz-1', updatedAt };
  useStore.setState({ state: { ...base, releases: [rel], order: [rel.id] } });
};

const trendValues = (option: Record<string, unknown> | null): number[] =>
  ((option?.series as { data: number[] }[])?.[0]?.data) ?? [];

describe('Dashboard 趋势图按所选时区分桶', () => {
  it('should_shift_day_bucket_when_timezone_switched', () => {
    // 仅冻结 Date（定时器保持真实），消除宿主时钟对分桶前提的窗口依赖
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(new Date(FROZEN_NOW));
      seedSingleRelease(BOUNDARY_INSTANT);
      act(() => {
        useTimezoneStore.getState().setTimezone('Asia/Shanghai');
      });
      const { result } = renderHook(() => useDashboardCharts());
      // 上海（UTC+8）：00:30 UTC = 当日 08:30 → 计入今天（末桶）
      expect(trendValues(result.current.trend).at(-1)).toBe(1);

      act(() => {
        useTimezoneStore.getState().setTimezone('America/New_York');
      });
      // 纽约（UTC-5）：00:30 UTC = 前一日 19:30 → 计入昨天（倒数第二桶），今天清零
      expect(trendValues(result.current.trend).at(-1)).toBe(0);
      expect(trendValues(result.current.trend).at(-2)).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });
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