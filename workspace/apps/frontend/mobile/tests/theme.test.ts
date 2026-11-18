import { describe, it, expect } from 'vitest';
import { DARK_NEUTRALS, TONES, PLATFORM_COLORS, ENV_COLORS, HEALTH_COLORS, HEALTH_LABELS } from '@acme/core';
import { ACCENTS, ACCENT_ORDER, palette, TONE_COLOR, PLATFORM_COLOR, ENV_COLOR, HEALTH_COLOR, HEALTH_LABEL } from '../src/theme';

describe('mobile theme derivation', () => {
  it('should_derive_palette_from_core_dark_neutrals', () => {
    expect(palette).toEqual({
      bg: DARK_NEUTRALS.bg,
      surface: DARK_NEUTRALS.surface,
      elevated: DARK_NEUTRALS.elevated,
      text: DARK_NEUTRALS.text,
      text2: DARK_NEUTRALS.text2,
      muted: DARK_NEUTRALS.muted,
      border: DARK_NEUTRALS.border,
    });
    expect(palette.bg).toBe('#14161a');
    expect(palette.muted).toBe('#8b929e');
  });

  it('should_keep_historical_semantic_maps', () => {
    expect(ACCENTS.cyan).toBe('#5aa7a0');
    expect(ACCENT_ORDER).toHaveLength(6);
    expect(TONE_COLOR).toBe(TONES);
    expect(PLATFORM_COLOR).toBe(PLATFORM_COLORS);
    expect(ENV_COLOR).toBe(ENV_COLORS);
    expect(HEALTH_COLOR).toBe(HEALTH_COLORS);
    expect(HEALTH_LABEL).toBe(HEALTH_LABELS);
    expect(TONE_COLOR.success).toBe('#6fa287');
    expect(PLATFORM_COLOR.desktop).toBe('#9188c9');
  });
});