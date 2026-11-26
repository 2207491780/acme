/**
 * REQ-20261126-125836 mobile 侧契约用例（AC-5）：releases Tab 与 acme://releases 深链删除。
 * 装配：纯函数直测（mobile/src/nav.ts），无需 RN 宿主替身。
 */
import { describe, expect, it } from 'vitest';
import { TAB_KEYS, TAB_PATHS, LINK_PREFIXES, resolveDeepLinkPath } from '../src/nav';

describe('REQ20261126125836 mobile 发布 Tab 删除（AC-5）', () => {
  it('should_fallback_to_dashboard_when_release_deeplink_opened', () => {
    expect(TAB_KEYS).not.toContain('releases');
    expect(Object.values(TAB_PATHS)).not.toContain('releases');
    expect(LINK_PREFIXES).toEqual(['acme://']);
    expect(resolveDeepLinkPath('releases')).toBe('dashboard');
    expect(resolveDeepLinkPath('/releases')).toBe('dashboard');
    expect(resolveDeepLinkPath('acme://releases')).toBe('dashboard');
    // 非发布深链不受影响
    expect(resolveDeepLinkPath('dashboard')).toBe('dashboard');
    expect(resolveDeepLinkPath('/apps')).toBe('apps');
  });
});
