/**
 * REQ-20261114-071607 AC-20：日期 formatter 单测——locale=zh-CN / en-US 两档输出锁定 + 格式可配置。
 *
 * 【假定 API】契约（§确认记录⑥、AC-20、变更清单前端节）只固定「dayjs + locale zh-CN/en-US、格式可配置」，
 * 未固定导出名与两档缺省格式；本用例按以下表面编写，TL 裁决后若有出入仅需同步调整（见交付含糊点清单 B）：
 *
 *   formatDate(
 *     value: string | number | Date | null | undefined,
 *     options?: { locale?: 'zh-CN' | 'en-US'; format?: string },
 *   ): string
 *   - 缺省 locale=zh-CN（产品既有文案为中文优先）
 *   - zh-CN 缺省格式 'YYYY-MM-DD HH:mm:ss'；en-US 缺省格式 'MMM D, YYYY h:mm:ss A'
 *   - 显式 format 覆盖 locale 缺省格式（格式可配置）
 *
 * 注：无效/空输入行为契约未定义，不在本用例断言范围（见含糊点清单 H）。
 */
import { describe, expect, it } from 'vitest';
import { formatDate } from '../src';

const iso = '2026-11-14T15:04:05';

describe('REQ20261114071607 日期 formatter', () => {
  it('should_render_zh_cn_datetime_when_locale_is_zh_cn', () => {
    expect(formatDate(iso, { locale: 'zh-CN' })).toBe('2026-11-14 15:04:05');
  });

  it('should_render_en_us_datetime_when_locale_is_en_us', () => {
    expect(formatDate(iso, { locale: 'en-US' })).toBe('Nov 14, 2026 3:04:05 PM');
  });

  it('should_switch_output_when_locale_changes', () => {
    const zh = formatDate(iso, { locale: 'zh-CN' });
    const en = formatDate(iso, { locale: 'en-US' });
    expect(zh).not.toBe(en);
    expect(zh).toBe('2026-11-14 15:04:05');
    expect(en).toBe('Nov 14, 2026 3:04:05 PM');
  });

  it('should_use_zh_cn_default_format_when_locale_omitted', () => {
    expect(formatDate(iso)).toBe('2026-11-14 15:04:05');
  });

  it('should_apply_custom_format_when_format_provided', () => {
    expect(formatDate(iso, { locale: 'en-US', format: 'YYYY/MM/DD' })).toBe('2026/11/14');
    expect(formatDate(iso, { locale: 'zh-CN', format: 'HH:mm' })).toBe('15:04');
    expect(formatDate(iso, { format: 'YYYY年MM月DD日' })).toBe('2026年11月14日');
  });

  it('should_accept_date_and_timestamp_when_value_is_not_iso_string', () => {
    const local = new Date(2026, 10, 14, 15, 4, 5);
    expect(formatDate(local, { locale: 'zh-CN' })).toBe('2026-11-14 15:04:05');
    expect(formatDate(local.getTime(), { locale: 'zh-CN' })).toBe('2026-11-14 15:04:05');
  });
});
