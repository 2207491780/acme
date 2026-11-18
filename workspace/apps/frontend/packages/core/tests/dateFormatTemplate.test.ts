/**
 * 日期国际化·模板维度：用户模板偏好独立于语言/时区（优先级：显式 format > 模板偏好 > locale 预置）。
 * 直连 '../src/date-format'（不经 barrel，node 环境安全）；锁定值以固定 UTC 瞬时 + 显式时区推算。
 */
import { afterEach, describe, expect, it } from 'vitest';
import {
  formatDate,
  formatDateOnly,
  getDateFormatTemplate,
  setDateLocale,
  setDateFormatTemplate,
  setTimezone,
} from '../src/date-format';

const INSTANT = '2026-11-14T03:05:09Z';

afterEach(() => {
  setDateFormatTemplate('auto');
  setTimezone('system');
  setDateLocale('zh-CN');
});

describe('日期时间模板偏好', () => {
  it('should_override_locale_preset_when_template_selected', () => {
    setTimezone('UTC');
    setDateFormatTemplate('YYYY/MM/DD HH:mm');
    expect(getDateFormatTemplate()).toBe('YYYY/MM/DD HH:mm');
    expect(formatDate(INSTANT)).toBe('2026/11/14 03:05');
    // 模板与语言独立：切 en-US 不改模板输出（数字模板 locale 中立）
    setDateLocale('en-US');
    expect(formatDate(INSTANT)).toBe('2026/11/14 03:05');
  });

  it('should_follow_locale_preset_when_template_auto', () => {
    setTimezone('UTC');
    setDateFormatTemplate('auto');
    expect(formatDate(INSTANT)).toBe('2026-11-14 03:05:09');
    setDateLocale('en-US');
    expect(formatDate(INSTANT)).toBe('Nov 14, 2026 3:05:09 AM');
  });

  it('should_keep_explicit_format_option_priority_when_template_set', () => {
    setTimezone('UTC');
    setDateFormatTemplate('YYYY/MM/DD HH:mm');
    expect(formatDate(INSTANT, { format: 'HH:mm' })).toBe('03:05');
  });

  it('should_not_apply_datetime_template_to_date_only', () => {
    setTimezone('UTC');
    setDateFormatTemplate('YYYY/MM/DD HH:mm');
    expect(formatDateOnly(INSTANT)).toBe('2026-11-14');
    setDateLocale('en-US');
    expect(formatDateOnly(INSTANT)).toBe('Nov 14, 2026');
  });

  it('should_combine_template_and_timezone_when_both_set', () => {
    setTimezone('Asia/Shanghai');
    setDateFormatTemplate('DD/MM/YYYY HH:mm');
    expect(formatDate(INSTANT)).toBe('14/11/2026 11:05');
  });
});