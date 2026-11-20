import { describe, it, expect } from 'vitest';
import { theme as antdTheme } from 'antd';
import type { Settings, ThemePreferences } from '@acme/core';
import { buildTheme, ACCENTS, ACCENT_ORDER } from '../src/theme';

// REQ-20261121-010725 终稿口径（TL 裁决）：Settings = { schemaVersion, preferences } + 4 个
// @deprecated 派生投影字段（theme/accent/density/motion，settingsFromPreferences 统一派生，
// preferences 唯一事实源）。fixture 以 preferences 派生投影，保证两段永不漂移。
const preferences = (over: Partial<ThemePreferences> = {}): ThemePreferences => ({
  mode: 'dark',
  accent: 'cyan',
  customAccent: null,
  density: 'comfortable',
  motionEnabled: true,
  ...over,
});
const settingsOf = (over: Partial<ThemePreferences> = {}): Settings => {
  const prefs = preferences(over);
  return {
    schemaVersion: 2,
    preferences: prefs,
    // 投影 theme 为旧两态（legacy 无 system 语义）；本文件用例仅用 light/dark
    theme: prefs.mode as 'light' | 'dark',
    accent: prefs.accent,
    density: prefs.density,
    motion: prefs.motionEnabled,
  };
};
const base: Settings = settingsOf();

describe('web theme derivation', () => {
  it('should_reexport_core_accents', () => {
    expect(ACCENTS.cyan).toBe('#5aa7a0');
    // REQ-20261121-010725：强调色 6 → 12（TL 钉死键值）
    expect(ACCENT_ORDER).toHaveLength(12);
    expect(ACCENTS.emerald).toBe('#58aa82');
    expect(ACCENTS.sky).toBe('#6aa8cf');
    expect(ACCENTS.coral).toBe('#cd7a63');
    expect(ACCENTS.ruby).toBe('#c56d6d');
    expect(ACCENTS.bronze).toBe('#b08968');
    expect(ACCENTS.slate).toBe('#8a9bb0');
  });

  it('should_preserve_verified_dark_cyan_comfortable_tokens', () => {
    const t = buildTheme(base);
    expect(t.algorithm).toBe(antdTheme.darkAlgorithm);
    expect(t.token).toMatchObject({
      colorPrimary: '#5aa7a0',
      colorInfo: '#5aa7a0',
      colorTextLightSolid: '#000000',
      borderRadius: 12,
      fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
      colorBgBase: '#14161a',
      colorBgLayout: '#14161a',
      colorBgContainer: '#1d2127',
      colorBgElevated: '#242932',
      colorText: '#e8eaed',
      colorTextSecondary: '#b6bcc6',
      colorBorder: 'rgba(255, 255, 255, 0.08)',
      colorBorderSecondary: 'rgba(255, 255, 255, 0.05)',
    });
    expect(t.components?.Layout).toMatchObject({ headerHeight: 64, headerPadding: '0 24px' });
    expect(t.components?.Menu).toMatchObject({ itemSelectedBg: '#5aa7a029', itemSelectedColor: '#5aa7a0' });
    expect(t.components?.Table).toMatchObject({
      headerBg: '#242932',
      rowHoverBg: 'rgba(90, 167, 160, 0.06)',
      rowSelectedBg: '#5aa7a01f',
      rowSelectedHoverBg: '#5aa7a029',
      cellPaddingBlock: 13,
      cellPaddingInline: 14,
    });
    expect(t.components?.Card).toMatchObject({ paddingLG: 20 });
  });

  it('should_theme_semantic_presets_to_muted_tones_in_both_themes', () => {
    for (const mode of ['light', 'dark'] as const) {
      const t = buildTheme(settingsOf({ mode }));
      expect(t.token).toMatchObject({
        colorSuccess: '#6fa287',
        colorWarning: '#cfa14f',
        colorError: '#c47b7b',
        purple: '#9188c9',
      });
    }
  });

  it.each([
    {
      mode: 'light' as const,
      expected: {
        colorSuccess: '#6fa287', colorSuccessBg: '#d5e3d9',
        colorWarning: '#cfa14f', colorWarningBg: '#fffcf0',
        colorError: '#c47b7b', colorErrorBg: '#fff2f0',
        purple1: '#f4f0ff', purple6: '#9188c9',
      },
    },
    {
      mode: 'dark' as const,
      expected: {
        colorSuccess: '#618d76', colorSuccessBg: '#191e1c',
        colorWarning: '#b38c46', colorWarningBg: '#251e16',
        colorError: '#aa6c6c', colorErrorBg: '#231b1b',
        purple1: '#1c1c24', purple6: '#7e77ae',
      },
    },
  ])('should_derive_muted_semantic_palettes_when_mode_is_$mode', ({ mode, expected }) => {
    expect(antdTheme.getDesignToken(buildTheme(settingsOf({ mode })))).toMatchObject(expected);
  });

  it('should_preserve_verified_light_blue_compact_tokens', () => {
    const t = buildTheme(settingsOf({ mode: 'light', density: 'compact', accent: 'blue' }));
    expect(t.algorithm).toBe(antdTheme.defaultAlgorithm);
    expect(t.token?.borderRadius).toBe(8);
    expect(t.token?.colorTextLightSolid).toBe('#000000');
    expect(t.token?.colorBgBase).toBeUndefined();
    expect(t.components?.Menu).toMatchObject({ itemSelectedBg: '#6f92c429' });
    expect(t.components?.Table).toMatchObject({
      headerBg: '#f4f5f7',
      rowHoverBg: 'rgba(111, 146, 196, 0.06)',
      cellPaddingBlock: 8,
      cellPaddingInline: 10,
    });
    expect(t.components?.Card).toMatchObject({ paddingLG: 16 });
  });
});
