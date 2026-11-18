/** @vitest-environment jsdom */
/**
 * timezoneStore：时区偏好持久化（acme.timezone.v1）+ date-format 全局缺省联动。
 * 口径照 localeStore：运行时校验（未知值拒收/损坏回退 system）+ 存储异常降级内存态。
 */
import { afterEach, describe, expect, it } from 'vitest';
import { getTimezone } from '../src/date-format';
import { loadPersistedTimezone, useTimezoneStore } from '../src/store/timezoneStore';

const KEY = 'acme.timezone.v1';

afterEach(() => {
  window.localStorage.clear();
  useTimezoneStore.getState().setTimezone('system');
});

describe('timezoneStore 持久化与联动', () => {
  it('should_persist_and_link_formatter_when_timezone_selected', () => {
    useTimezoneStore.getState().setTimezone('Asia/Shanghai');
    expect(useTimezoneStore.getState().timezone).toBe('Asia/Shanghai');
    expect(getTimezone()).toBe('Asia/Shanghai'); // date-format 全局缺省联动
    expect(window.localStorage.getItem(KEY)).toContain('Asia/Shanghai');
  });

  it('should_reject_unknown_timezone_when_set', () => {
    useTimezoneStore.getState().setTimezone('Asia/Shanghai');
    useTimezoneStore.getState().setTimezone('Mars/Olympus' as never);
    expect(useTimezoneStore.getState().timezone).toBe('Asia/Shanghai');
    expect(getTimezone()).toBe('Asia/Shanghai');
  });

  it('should_fallback_system_when_persisted_value_corrupt', () => {
    window.localStorage.setItem(KEY, '{broken json');
    expect(loadPersistedTimezone()).toBe('system');
    window.localStorage.setItem(KEY, '"Mars/Olympus"');
    expect(loadPersistedTimezone()).toBe('system');
    window.localStorage.setItem(KEY, '"UTC"');
    expect(loadPersistedTimezone()).toBe('UTC');
    window.localStorage.setItem(KEY, '{"timezone":"Asia/Tokyo"}');
    expect(loadPersistedTimezone()).toBe('Asia/Tokyo');
  });
});