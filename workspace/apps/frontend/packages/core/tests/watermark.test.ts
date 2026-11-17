import { afterEach, describe, expect, it } from 'vitest';
import {
  WATERMARK_PLACEHOLDERS,
  WATERMARK_TEMPLATE_MAX_LENGTH,
  findUnknownWatermarkPlaceholders,
  isSupportedWatermarkPlaceholder,
  renderWatermarkTemplate,
  type WatermarkContext,
} from '../src/watermark';
import {
  setDateFormatTemplate,
  setDateLocale,
  setTimezone,
  SYSTEM_TIMEZONE,
} from '../src/date-format';

/**
 * 水印模板渲染器（REQ-20261117-180000）：占位符替换 / 缺省仅用户名 / 昵称回退 /
 * date·time 随语言与时区偏好 / 未知占位符校验与字面量防御。
 * 时间用例以固定 epoch + 显式时区构造，避免运行环境时区差异导致断言漂移。
 */
describe('watermark', () => {
  afterEach(() => {
    setDateLocale('zh-CN');
    setTimezone(SYSTEM_TIMEZONE);
    setDateFormatTemplate('auto');
  });

  const ctx: WatermarkContext = {
    username: 'zhangsan',
    nickname: '张三',
    userId: '1990001',
    tenantCode: 'acme',
    deptName: '研发部',
  };

  // 2026-11-14 03:05:09 UTC（epoch 固定）
  const now = Date.UTC(2026, 10, 14, 3, 5, 9);

  it('should_render_username_only_when_template_null_or_empty', () => {
    expect(renderWatermarkTemplate(null, ctx, now)).toBe('zhangsan');
    expect(renderWatermarkTemplate(undefined, ctx, now)).toBe('zhangsan');
    expect(renderWatermarkTemplate('', ctx, now)).toBe('zhangsan');
  });

  it('should_replace_all_supported_placeholders', () => {
    setTimezone('UTC');
    const template = '{username}|{nickname}|{userId}|{tenantCode}|{deptName}|{date}|{time}';
    expect(renderWatermarkTemplate(template, ctx, now))
      .toBe('zhangsan|张三|1990001|acme|研发部|2026-11-14|03:05');
  });

  it('should_fallback_nickname_to_username_when_empty', () => {
    expect(renderWatermarkTemplate('{nickname}', { ...ctx, nickname: null }, now)).toBe('zhangsan');
    expect(renderWatermarkTemplate('{nickname}', { ...ctx, nickname: '' }, now)).toBe('zhangsan');
    expect(renderWatermarkTemplate('{nickname}', { username: 'solo' }, now)).toBe('solo');
  });

  it('should_render_empty_for_missing_optional_identity_fields', () => {
    expect(renderWatermarkTemplate('[{userId}][{tenantCode}][{deptName}]', { username: 'solo' }, now))
      .toBe('[][][]');
  });

  it('should_follow_locale_for_date_and_time', () => {
    setTimezone('UTC');
    setDateLocale('en-US');
    expect(renderWatermarkTemplate('{date} {time}', ctx, now)).toBe('Nov 14, 2026 3:05 AM');
    setDateLocale('zh-CN');
    expect(renderWatermarkTemplate('{date} {time}', ctx, now)).toBe('2026-11-14 03:05');
  });

  it('should_follow_timezone_preference_for_date_and_time', () => {
    setTimezone('Asia/Shanghai');
    expect(renderWatermarkTemplate('{date} {time}', ctx, now)).toBe('2026-11-14 11:05');
    setTimezone('America/New_York');
    expect(renderWatermarkTemplate('{date} {time}', ctx, now)).toBe('2026-11-13 22:05');
  });

  it('should_ignore_datetime_template_preference_for_date_and_time', () => {
    // {date}/{time} 为纯日期/纯时间展示，不受日期时间模板偏好影响（同 formatDateOnly 口径）
    setTimezone('UTC');
    setDateFormatTemplate('DD/MM/YYYY HH:mm');
    expect(renderWatermarkTemplate('{date} {time}', ctx, now)).toBe('2026-11-14 03:05');
  });

  it('should_keep_unknown_and_incomplete_tokens_as_literal', () => {
    expect(renderWatermarkTemplate('{bogus} {username', ctx, now)).toBe('{bogus} {username');
    expect(renderWatermarkTemplate('ACME 内部系统', ctx, now)).toBe('ACME 内部系统');
  });

  it('should_list_unknown_complete_placeholders_for_validation', () => {
    expect(findUnknownWatermarkPlaceholders('{username} {date} {time}')).toEqual([]);
    expect(findUnknownWatermarkPlaceholders('{bogus} {User} {}')).toEqual(['bogus', 'User', '']);
    // 残缺花括号不构成完整 token，不报未知
    expect(findUnknownWatermarkPlaceholders('{username')).toEqual([]);
  });

  it('should_expose_placeholder_set_aligned_with_backend', () => {
    expect([...WATERMARK_PLACEHOLDERS])
      .toEqual(['username', 'nickname', 'userId', 'tenantCode', 'deptName', 'date', 'time']);
    expect(WATERMARK_TEMPLATE_MAX_LENGTH).toBe(255);
    expect(isSupportedWatermarkPlaceholder('username')).toBe(true);
    expect(isSupportedWatermarkPlaceholder('bogus')).toBe(false);
    expect(isSupportedWatermarkPlaceholder(null)).toBe(false);
  });
});