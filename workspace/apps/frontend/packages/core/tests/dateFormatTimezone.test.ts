/**
 * 日期国际化·时区维度：formatDate/formatDateOnly/zonedDayjs 随全局时区偏好换算。
 * 直连 '../src/date-format'（不经 barrel，node 环境安全）。
 * 锁定值以固定 UTC 瞬时推算（与运行环境本地时区无关）。
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  effectiveTimezone,
  formatDate,
  formatDateOnly,
  getTimezone,
  setDateLocale,
  setTimezone,
  zonedDayjs,
} from '../src/date-format';

const INSTANT = '2026-11-14T03:05:09Z'; // UTC 2026-11-14 03:05:09（11 月纽约已入 EST UTC-5）

afterEach(() => {
  setTimezone('system');
  setDateLocale('zh-CN');
});

describe('formatDate 随全局时区换算', () => {
  it('should_convert_instant_when_timezone_selected', () => {
    setTimezone('UTC');
    expect(formatDate(INSTANT)).toBe('2026-11-14 03:05:09');
    setTimezone('Asia/Shanghai');
    expect(formatDate(INSTANT)).toBe('2026-11-14 11:05:09');
    setTimezone('Asia/Tokyo');
    expect(formatDate(INSTANT)).toBe('2026-11-14 12:05:09');
    setTimezone('America/New_York');
    expect(formatDate(INSTANT)).toBe('2026-11-13 22:05:09'); // 跨日界
  });

  it('should_combine_locale_and_timezone_when_both_set', () => {
    setTimezone('Asia/Shanghai');
    setDateLocale('en-US');
    expect(formatDate(INSTANT)).toBe('Nov 14, 2026 11:05:09 AM');
    expect(formatDateOnly(INSTANT)).toBe('Nov 14, 2026');
    setTimezone('America/New_York');
    expect(formatDateOnly(INSTANT)).toBe('Nov 13, 2026');
  });

  it('should_follow_system_timezone_when_pref_system', () => {
    setTimezone('system');
    expect(getTimezone()).toBe('system');
    expect(effectiveTimezone()).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    // 跟随系统 = 本地墙钟（与既有 formatter 本地时间口径一致）
    const d = new Date(INSTANT);
    const pad = (n: number) => String(n).padStart(2, '0');
    expect(formatDate(INSTANT)).toBe(
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`,
    );
  });

  it('should_return_placeholder_when_input_invalid', () => {
    setTimezone('Asia/Shanghai');
    expect(formatDate('not-a-date')).toBe('—');
    expect(formatDate(null)).toBe('—');
  });
});

describe('zonedDayjs 时区感知日期运算（图表按日分桶出口）', () => {
  it('should_bucket_day_key_by_selected_timezone', () => {
    setTimezone('Asia/Shanghai');
    expect(zonedDayjs(INSTANT).format('YYYY-MM-DD')).toBe('2026-11-14');
    setTimezone('America/New_York');
    expect(zonedDayjs(INSTANT).format('YYYY-MM-DD')).toBe('2026-11-13');
    setTimezone('UTC');
    expect(zonedDayjs(INSTANT).format('YYYY-MM-DD')).toBe('2026-11-14');
  });

  it('should_subtract_days_without_dst_drift', () => {
    setTimezone('America/New_York');
    // 2026-11-01 纽约 DST 结束；跨 DST 边界的按日减法仍落正确日界
    const d = zonedDayjs('2026-11-02T12:00:00Z').subtract(2, 'day');
    expect(d.format('YYYY-MM-DD')).toBe('2026-10-31');
  });
});