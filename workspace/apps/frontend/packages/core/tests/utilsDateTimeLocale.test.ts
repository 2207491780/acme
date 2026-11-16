/**
 * 日期国际化补全：utils 的 relTime / fmtFull 随全局日期 locale 切换
 * （此前 relTime 中文硬编码、fmtFull 固定 zh 模板；现统一委托 date-format 口径）。
 * 直连 '../src/utils' + '../src/date-format'（不经 barrel，node 环境安全）。
 * 用例均以本地时间构造输入，避免时区漂移（既有 formatter 测试口径）。
 */
import { afterEach, describe, expect, it } from 'vitest';
import { fmtDate, fmtFull, relTime } from '../src/utils';
import { formatDate, setDateLocale } from '../src/date-format';

const NOW = new Date(2026, 10, 14, 3, 5, 9).getTime(); // 2026-11-14 03:05:09 本地时间
const ago = (ms: number) => new Date(NOW - ms).toISOString();
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

afterEach(() => {
  setDateLocale('zh-CN');
});

describe('relTime 随日期 locale 切换', () => {
  it('should_keep_zh_copy_when_locale_zh_cn', () => {
    setDateLocale('zh-CN');
    expect(relTime(ago(30_000), NOW)).toBe('刚刚');
    expect(relTime(ago(5 * MINUTE), NOW)).toBe('5 分钟前');
    expect(relTime(ago(3 * HOUR), NOW)).toBe('3 小时前');
    expect(relTime(ago(2 * DAY), NOW)).toBe('2 天前');
  });

  it('should_switch_to_en_copy_when_locale_en_us', () => {
    setDateLocale('en-US');
    expect(relTime(ago(30_000), NOW)).toBe('just now');
    expect(relTime(ago(1 * MINUTE), NOW)).toBe('1 minute ago');
    expect(relTime(ago(5 * MINUTE), NOW)).toBe('5 minutes ago');
    expect(relTime(ago(1 * HOUR), NOW)).toBe('1 hour ago');
    expect(relTime(ago(3 * HOUR), NOW)).toBe('3 hours ago');
    expect(relTime(ago(1 * DAY), NOW)).toBe('1 day ago');
    expect(relTime(ago(2 * DAY), NOW)).toBe('2 days ago');
  });

  it('should_fallback_to_locale_aware_format_when_over_30_days', () => {
    const iso = ago(40 * DAY);
    setDateLocale('zh-CN');
    expect(relTime(iso, NOW)).toBe(formatDate(iso));
    setDateLocale('en-US');
    expect(relTime(iso, NOW)).toBe(formatDate(iso));
  });

  it('should_return_placeholder_when_input_invalid', () => {
    expect(relTime('not-a-date', NOW)).toBe('—');
  });
});

describe('fmtFull 委托统一日期 formatter', () => {
  it('should_follow_locale_template_when_formatting_full_datetime', () => {
    const iso = new Date(2026, 10, 14, 3, 5, 9).toISOString();
    setDateLocale('zh-CN');
    expect(fmtFull(iso)).toBe('2026-11-14 03:05:09');
    setDateLocale('en-US');
    expect(fmtFull(iso)).toBe('Nov 14, 2026 3:05:09 AM');
  });

  it('should_return_placeholder_when_input_invalid', () => {
    expect(fmtFull('not-a-date')).toBe('—');
  });
});

describe('fmtDate 保持数字短格式（locale 中立，原型归档沿用）', () => {
  it('should_keep_numeric_short_format_when_locale_switched', () => {
    const iso = new Date(2026, 10, 14, 3, 5, 9).toISOString();
    setDateLocale('en-US');
    expect(fmtDate(iso)).toBe('11/14 03:05');
  });
});