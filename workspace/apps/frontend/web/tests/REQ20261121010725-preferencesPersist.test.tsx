/**
 * REQ-20261121-010725 AC-6/AC-8 重载口径 + AC-3 web 迁移接线：偏好 blob 持久化与重载恢复。
 *
 * 独立于抽屉交互文件：不渲染 App，直接驱动 zustand 单例 store（契约钉死面：
 * settings 在 AppState 内 + UPDATE_SETTINGS patch 合并 + 整 blob 持久化 key=acme.frontend.ops.v1）。
 * 以 vi.resetModules + 动态重导入模拟「刷新重载」；jsdom 真实 localStorage 跨模块实例同源。
 *
 * 文件仅动态 import store（无静态引用），resetModules 后重新加载得到全新实例——
 * 其水合路径即应用重启后的恢复路径。
 *
 * REQ-20261121-043502（AC-10 回归口径适配）：偏好模型 v3 —— CUSTOM_PREFS 补 v3 字段
 * （radius/grayMode/weakMode/contentWidth/semanticColors），legacy 迁移产物 schemaVersion=3。
 * v3 新字段的专项用例见 REQ20261121043502-appearanceDrawer.test.tsx。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { waitFor } from '@testing-library/react';
import { DEFAULT_PREFERENCES, PREFERENCES_VERSION, STORAGE_KEY, type ThemePreferences } from '@acme/core';

vi.setConfig({ testTimeout: 20000 });

type StoreMod = typeof import('../src/store/useStore');
type SettingsLike = { schemaVersion?: number; preferences?: Record<string, unknown> };

/** 测试用自定义偏好（与默认完全可区分；显式类型标注防 string→联合 TS2322）。
 *  v4 适配（REQ-20261122-233427）：补 v4 四字段（取非默认值保持可区分语义）。 */
const CUSTOM_PREFS: ThemePreferences = {
  mode: 'dark',
  accent: 'emerald',
  customAccent: '#b08968',
  density: 'compact',
  motionEnabled: false,
  radius: 'full',
  grayMode: true,
  weakMode: true,
  contentWidth: 'compact',
  semanticColors: { success: '#22c55e', warning: null, danger: '#c2410c' },
  layoutMode: 'top',
  tabStyle: 'chrome',
  siderTone: 'dark',
  topbarWidgets: {
    search: false, bell: false, lang: true, timezone: false, appearance: true,
    themeToggle: false, lock: true, fullscreen: false, settings: true, release: false,
  },
};

/** blob 内 settings 段（容忍 zustand persist 信封包裹，其余严格）。 */
const readBlobSettings = (): Record<string, unknown> | null => {
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const holder = (parsed['state'] && typeof parsed['state'] === 'object'
      ? parsed['state'] : parsed) as Record<string, unknown>;
    return (holder['settings'] as Record<string, unknown> | undefined) ?? null;
  } catch {
    return null;
  }
};

/** 重载后的 store 实例设置段。 */
const readReloadedSettings = (mod: StoreMod): SettingsLike =>
  mod.useStore.getState().state.settings as unknown as SettingsLike;

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
});

afterEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  vi.resetModules();
});

describe('REQ20261121010725 持久化键（契约钉死）', () => {
  it('should_persist_preferences_into_pinned_ops_blob_key', () => {
    expect(STORAGE_KEY).toBe('acme.frontend.ops.v1');
    expect(DEFAULT_PREFERENCES).toEqual({
      mode: 'light', accent: 'cyan', customAccent: null, density: 'comfortable', motionEnabled: true,
      radius: 'medium', grayMode: false, weakMode: false, contentWidth: 'fluid',
      semanticColors: { success: null, warning: null, danger: null },
      layoutMode: 'sidebar', tabStyle: 'line', siderTone: 'auto',
      topbarWidgets: {
        search: true, bell: true, lang: true, timezone: true, appearance: true,
        themeToggle: true, lock: true, fullscreen: true, settings: true, release: true,
      },
    });
  });
});

describe('REQ20261121010725 偏好重载恢复（AC-6 刷新保持 / AC-8 重载保留）', () => {
  it('should_restore_persisted_preferences_when_store_reloads_from_blob', async () => {
    const first = (await import('../src/store/useStore')) as StoreMod;
    const ok = first.useStore.getState().dispatch({
      type: 'UPDATE_SETTINGS',
      patch: { preferences: { ...CUSTOM_PREFS } },
    });
    expect(ok).toBe(true);

    // 即时持久化：blob 内 settings.preferences 已是自定义偏好（含 v3 字段，043502）
    await waitFor(() => expect(readBlobSettings()).toBeTruthy());
    const persisted = JSON.stringify(readBlobSettings());
    expect(persisted).toContain('"mode":"dark"');
    expect(persisted).toContain('"accent":"emerald"');
    expect(persisted).toContain('"customAccent":"#b08968"');
    expect(persisted).toContain('"density":"compact"');
    expect(persisted).toContain('"motionEnabled":false');
    expect(persisted).toContain('"radius":"full"');
    expect(persisted).toContain('"grayMode":true');
    expect(persisted).toContain('"weakMode":true');
    expect(persisted).toContain('"contentWidth":"compact"');
    expect(persisted).toContain('"semanticColors"');

    // 重载：resetModules 后重新 import，偏好从 blob 恢复（刷新后保持）
    vi.resetModules();
    const reloaded = (await import('../src/store/useStore')) as StoreMod;
    await waitFor(() => {
      expect(readReloadedSettings(reloaded)?.preferences).toEqual(CUSTOM_PREFS);
    });
  });

  it('should_migrate_legacy_blob_settings_on_reload_when_version_missing', async () => {
    // 先落一份种子 blob，再把 settings 段替换为 legacy 旧语义（契约 AC-3：dark/accent/density/motion）
    await import('../src/store/useStore'); // 空存储 → 播种默认 blob
    await waitFor(() => expect(readBlobSettings()).toBeTruthy());
    const blob = JSON.parse(window.localStorage.getItem(STORAGE_KEY) as string) as Record<string, unknown>;
    const holder = (blob['state'] && typeof blob['state'] === 'object'
      ? blob['state'] : blob) as Record<string, unknown>;
    holder['settings'] = { dark: true, accent: 'violet', density: 'compact', motion: false };
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(blob));

    // 重载：无版本 legacy settings 应被迁移接线无损映射为新 shape（043502/233427：直迁当前版本，新字段补默认）
    vi.resetModules();
    const reloaded = (await import('../src/store/useStore')) as StoreMod;
    await waitFor(() => {
      expect(readReloadedSettings(reloaded)?.preferences).toEqual({
        mode: 'dark', accent: 'violet', customAccent: null, density: 'compact', motionEnabled: false,
        radius: 'medium', grayMode: false, weakMode: false, contentWidth: 'fluid',
        semanticColors: { success: null, warning: null, danger: null },
        layoutMode: 'sidebar', tabStyle: 'line', siderTone: 'auto',
        topbarWidgets: {
          search: true, bell: true, lang: true, timezone: true, appearance: true,
          themeToggle: true, lock: true, fullscreen: true, settings: true, release: true,
        },
      });
    });
    expect(readReloadedSettings(reloaded)?.schemaVersion).toBe(PREFERENCES_VERSION);
  });
});
