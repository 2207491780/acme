import { afterEach, describe, expect, it } from 'vitest';
import {
  DATE_PLACEHOLDER,
  formatDate,
  formatDateOnly,
  getDateLocale,
  setDateLocale,
} from '../src/date-format';

/**
 * 统一日期 formatter（日期国际化）：锁定 zh-CN / en-US 两种 locale 输出。
 * 用例均以本地时间构造输入，避免时区差异导致断言漂移。
 */
describe('date-format', () => {
  afterEach(() => {
    // 恢复全局缺省，避免用例间串扰
    setDateLocale('zh-CN');
  });

  const localTime = () => new Date(2026, 10, 14, 3, 5, 9); // 2026-11-14 03:05:09 本地时间
  const afternoon = () => new Date(2026, 10, 14, 15, 5, 9); // 2026-11-14 15:05:09 本地时间

  it('should_format_datetime_zh_cn_by_default', () => {
    expect(formatDate(localTime())).toBe('2026-11-14 03:05:09');
    expect(getDateLocale()).toBe('zh-CN');
  });

  it('should_format_datetime_en_us_when_locale_switched', () => {
    expect(formatDate(localTime(), { locale: 'en-US' })).toBe('Nov 14, 2026 3:05:09 AM');
    expect(formatDate(afternoon(), { locale: 'en-US' })).toBe('Nov 14, 2026 3:05:09 PM');
  });

  it('should_switch_locale_globally_via_set_date_locale', () => {
    setDateLocale('en-US');
    expect(getDateLocale()).toBe('en-US');
    expect(formatDate(localTime())).toBe('Nov 14, 2026 3:05:09 AM');
    // 显式 locale 优先于全局缺省
    expect(formatDate(localTime(), { locale: 'zh-CN' })).toBe('2026-11-14 03:05:09');
    setDateLocale('zh-CN');
    expect(formatDate(localTime())).toBe('2026-11-14 03:05:09');
  });

  it('should_format_date_only_per_locale', () => {
    expect(formatDateOnly(localTime())).toBe('2026-11-14');
    expect(formatDateOnly(localTime(), { locale: 'en-US' })).toBe('Nov 14, 2026');
  });

  it('should_support_custom_format_override', () => {
    expect(formatDate(localTime(), { format: 'YYYY/MM/DD' })).toBe('2026/11/14');
    expect(formatDate(localTime(), { locale: 'en-US', format: 'YYYY/MM/DD' })).toBe('2026/11/14');
  });

  it('should_accept_iso_string_and_epoch_inputs', () => {
    const base = new Date(2026, 10, 14, 3, 5, 9);
    expect(formatDate(base.toISOString())).toBe('2026-11-14 03:05:09');
    expect(formatDate(base.getTime())).toBe('2026-11-14 03:05:09');
    expect(formatDate(base)).toBe('2026-11-14 03:05:09');
  });

  it('should_fall_back_to_placeholder_for_empty_or_invalid_input', () => {
    expect(formatDate(null)).toBe(DATE_PLACEHOLDER);
    expect(formatDate(undefined)).toBe(DATE_PLACEHOLDER);
    expect(formatDate('')).toBe(DATE_PLACEHOLDER);
    expect(formatDate('not-a-date')).toBe(DATE_PLACEHOLDER);
    expect(formatDateOnly('not-a-date')).toBe(DATE_PLACEHOLDER);
  });
});
