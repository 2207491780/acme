// Mobile 主题：全部从 @acme/core 设计令牌派生，保持与 Web 同源。
// 导出名沿用历史 API（palette / TONE_COLOR / ...），组件无需改动。
import {
  ACCENTS,
  ACCENT_ORDER,
  ON_ACCENT,
  DARK_NEUTRALS,
  TONES,
  PLATFORM_COLORS,
  ENV_COLORS,
  HEALTH_COLORS,
  HEALTH_LABELS,
} from '@acme/core';

export { ACCENTS, ACCENT_ORDER, ON_ACCENT };

// 实心强调色之上的前景：当前各色统一为近黑，静态样式（图标/徽标/色板点）直接取此值。
export const onAccent = ON_ACCENT.cyan;

export const palette = {
  bg: DARK_NEUTRALS.bg,
  surface: DARK_NEUTRALS.surface,
  elevated: DARK_NEUTRALS.elevated,
  text: DARK_NEUTRALS.text,
  text2: DARK_NEUTRALS.text2,
  muted: DARK_NEUTRALS.muted,
  border: DARK_NEUTRALS.border,
};

export const TONE_COLOR = TONES;
export const PLATFORM_COLOR = PLATFORM_COLORS;
export const ENV_COLOR = ENV_COLORS;
export const HEALTH_COLOR = HEALTH_COLORS;
export const HEALTH_LABEL = HEALTH_LABELS;