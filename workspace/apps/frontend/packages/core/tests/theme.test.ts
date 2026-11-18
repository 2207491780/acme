import { describe, it, expect } from 'vitest';
import {
  ACCENTS,
  ACCENT_ORDER,
  ON_ACCENT,
  ACCENT_COMPANIONS,
  TONES,
  TONE_ANTD,
  PLATFORM_COLORS,
  ENV_COLORS,
  HEALTH_COLORS,
  HEALTH_LABELS,
  DARK_NEUTRALS,
  LIGHT_NEUTRALS,
  resolvePalette,
  DENSITY,
  FONT_FAMILY,
  withAlpha,
  mixHex,
  statusColor,
  GRADIENTS,
} from '../src';

describe('theme tokens', () => {
  it('should_expose_six_accents_in_order', () => {
    expect(ACCENT_ORDER).toHaveLength(6);
    expect(Object.keys(ACCENTS)).toEqual(ACCENT_ORDER);
    expect(ACCENTS.cyan).toBe('#5aa7a0');
    expect(ON_ACCENT.cyan).toBe('#000000');
    expect(ACCENT_COMPANIONS.cyan.secondary).toBe('#6f92c4');
  });

  it('should_map_status_to_semantic_tone_color', () => {
    expect(statusColor('published')).toBe(TONES.success);
    expect(statusColor('failed')).toBe(TONES.danger);
    expect(statusColor('building')).toBe(TONES.info);
    expect(statusColor('draft')).toBe(TONES.muted);
    expect(statusColor('unknown')).toBe(TONES.muted);
  });

  it('should_keep_antd_name_maps_complete', () => {
    expect(TONE_ANTD.success).toBe('success');
    expect(TONE_ANTD.danger).toBe('error');
    expect(Object.keys(PLATFORM_COLORS)).toEqual(['web', 'desktop', 'mobile']);
    expect(Object.keys(ENV_COLORS)).toEqual(['dev', 'test', 'prod']);
    expect(Object.keys(HEALTH_COLORS)).toEqual(['healthy', 'warning', 'critical']);
    expect(HEALTH_LABELS.critical).toBe('异常');
  });

  it('should_resolve_neutral_palette_by_theme', () => {
    expect(resolvePalette('dark')).toBe(DARK_NEUTRALS);
    expect(resolvePalette('light')).toBe(LIGHT_NEUTRALS);
    expect(DARK_NEUTRALS.surface).toBe('#1d2127');
    expect(LIGHT_NEUTRALS.surface).toBe('#ffffff');
    expect(DARK_NEUTRALS.muted).toBe(TONES.muted);
  });

  it('should_match_density_presets_to_antd_values', () => {
    expect(DENSITY.comfortable.radius).toBe(12);
    expect(DENSITY.compact.radius).toBe(8);
    expect(DENSITY.comfortable.cardPadding).toBe(20);
    expect(DENSITY.compact.cellBlock).toBe(8);
    expect(DENSITY.comfortable.cellInline).toBe(14);
  });

  it('should_convert_hex_to_rgba', () => {
    expect(withAlpha('#5aa7a0', 0.06)).toBe('rgba(90, 167, 160, 0.06)');
    expect(withAlpha('#fff', 1)).toBe('rgba(255, 255, 255, 1)');
  });

  it('should_mix_hex_like_color_mix', () => {
    expect(mixHex('#000000', '#ffffff', 0.5)).toBe('#808080');
    expect(mixHex('#5aa7a0', '#1d2127', 1)).toBe('#5aa7a0');
    expect(mixHex('#5aa7a0', '#1d2127', 0)).toBe('#1d2127');
  });

  it('should_build_gradient_recipes', () => {
    expect(GRADIENTS.brand('cyan')).toContain('#5aa7a0');
    expect(GRADIENTS.brand('cyan')).toMatch(/^linear-gradient\(135deg,/);
    expect(GRADIENTS.hero('cyan', 'dark')).toMatch(/^linear-gradient\(160deg,/);
    expect(GRADIENTS.text('violet')).toContain('#9188c9');
  });

  it('should_share_font_family_with_web', () => {
    expect(FONT_FAMILY.sans).toBe('"Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif');
    expect(FONT_FAMILY.mono).toContain('Consolas');
  });
});