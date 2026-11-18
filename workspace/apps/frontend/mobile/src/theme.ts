// Mobile 主题：全部从 @acme/core 设计令牌派生，保持与 Web 同源。
// 导出名沿用历史 API（palette / TONE_COLOR / ...）；REQ-20261119-032108 起双主题：
// ThemeProvider 消费 core settings.theme，经 resolvePalette 派生 palette（dark→DARK_NEUTRALS / light→LIGHT_NEUTRALS）。
// 静态导出 palette 保留 dark 缺省（历史兼容 / 测试锚点），组件一律经 useAppTheme() 取主题派生值。
import { createContext, useContext, useMemo, createElement, type ReactNode } from 'react';
import {
  ACCENTS,
  ACCENT_ORDER,
  ON_ACCENT,
  DARK_NEUTRALS,
  resolvePalette,
  TONES,
  PLATFORM_COLORS,
  ENV_COLORS,
  HEALTH_COLORS,
  HEALTH_LABELS,
  type NeutralPalette,
  type Theme,
} from '@acme/core';
import { useStore } from './store/useStore';

export { ACCENTS, ACCENT_ORDER, ON_ACCENT };

// 实心强调色之上的前景：当前各色统一为近黑，静态样式（图标/徽标/色板点）直接取此值。
export const onAccent = ON_ACCENT.cyan;

// 历史 API：静态 dark 派生（缺省主题）。组件不得新增依赖此导出的样式——改用 useAppTheme()。
export const palette = {
  bg: DARK_NEUTRALS.bg,
  surface: DARK_NEUTRALS.surface,
  elevated: DARK_NEUTRALS.elevated,
  text: DARK_NEUTRALS.text,
  text2: DARK_NEUTRALS.text2,
  muted: DARK_NEUTRALS.muted,
  border: DARK_NEUTRALS.border,
};

// 主题派生值（含完整 NeutralPalette + 当前主题模式）。
export interface AppThemeValue {
  theme: Theme;
  palette: NeutralPalette;
}

// 缺省值 = dark（与历史静态 palette 同源；未包 ThemeProvider 时组件仍可安全渲染）。
const DARK_THEME_VALUE: AppThemeValue = { theme: 'dark', palette: DARK_NEUTRALS };

const ThemeContext = createContext<AppThemeValue>(DARK_THEME_VALUE);

/** 主题 Provider：消费 core settings.theme（useStore），经 core resolvePalette 派生 palette。
 *  （.ts 文件用 createElement，保持既有 `../theme` 导入路径不变。） */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const theme = useStore((s) => s.state.settings.theme);
  const value = useMemo<AppThemeValue>(() => ({ theme, palette: resolvePalette(theme) }), [theme]);
  return createElement(ThemeContext.Provider, { value }, children);
}

/** 当前主题派生值（palette + theme 模式）。UI 样式一律由此取中性色，禁再引静态 palette。 */
export function useAppTheme(): AppThemeValue {
  return useContext(ThemeContext);
}

/** 契约②别名（useThemeTheme）。 */
export const useThemeTheme = useAppTheme;

/** StatusBar 联动：dark→light-content（亮字压深底），light→dark-content。 */
export function barStyleFor(theme: Theme): 'light-content' | 'dark-content' {
  return theme === 'light' ? 'dark-content' : 'light-content';
}

export const TONE_COLOR = TONES;
export const PLATFORM_COLOR = PLATFORM_COLORS;
export const ENV_COLOR = ENV_COLORS;
export const HEALTH_COLOR = HEALTH_COLORS;
export const HEALTH_LABEL = HEALTH_LABELS;
