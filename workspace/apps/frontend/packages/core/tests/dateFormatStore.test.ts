/** @vitest-environment jsdom */
/**
 * dateFormatStore：日期时间模板偏好持久化（acme.dateformat.v1）+ date-format 全局缺省联动。
 * 口径照 localeStore/timezoneStore：运行时校验（未知值拒收/损坏回退 auto）+ 存储异常降级内存态。
 */
import { afterEach, describe, expect, it } from 'vitest';
import { getDateFormatTemplate } from '../src/date-format';
import { loadPersistedDateFormat, useDateFormatStore } from '../src/store/dateFormatStore';

const KEY = 'acme.dateformat.v1';

afterEach(() => {
  window.localStorage.clear();
  useDateFormatStore.getState().setTemplate('auto');
});

describe('dateFormatStore 持久化与联动', () => {
  it('should_persist_and_link_formatter_when_template_selected', () => {
    useDateFormatStore.getState().setTemplate('YYYY/MM/DD HH:mm');
    expect(useDateFormatStore.getState().template).toBe('YYYY/MM/DD HH:mm');
    expect(getDateFormatTemplate()).toBe('YYYY/MM/DD HH:mm');
    expect(window.localStorage.getItem(KEY)).toContain('YYYY/MM/DD HH:mm');
  });

  it('should_reject_unknown_template_when_set', () => {
    useDateFormatStore.getState().setTemplate('YYYY/MM/DD HH:mm');
    useDateFormatStore.getState().setTemplate('QQQQ-XX-YY' as never);
    expect(useDateFormatStore.getState().template).toBe('YYYY/MM/DD HH:mm');
    expect(getDateFormatTemplate()).toBe('YYYY/MM/DD HH:mm');
  });

  it('should_fallback_auto_when_persisted_value_corrupt', () => {
    window.localStorage.setItem(KEY, '{broken json');
    expect(loadPersistedDateFormat()).toBe('auto');
    window.localStorage.setItem(KEY, '"QQQQ-XX-YY"');
    expect(loadPersistedDateFormat()).toBe('auto');
    window.localStorage.setItem(KEY, '"DD/MM/YYYY HH:mm"');
    expect(loadPersistedDateFormat()).toBe('DD/MM/YYYY HH:mm');
    window.localStorage.setItem(KEY, '{"template":"MM/DD/YYYY h:mm A"}');
    expect(loadPersistedDateFormat()).toBe('MM/DD/YYYY h:mm A');
  });
});