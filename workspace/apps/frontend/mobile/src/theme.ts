// Mobile 主题：全部从 @acme/core 设计令牌派生，保持与 Web 同源。
// 导出名沿用历史 API（palette / TONE_COLOR / ...）；REQ-20261119-032108 起双主题：
// ThemeProvider 消费 core preferences.mode，经 resolveThemeMode 解析生效主题（system=跟随
// useColorScheme，系统切换即时生效）并经 resolvePalette 派生 palette。
// REQ-20261121-200415 THEME-3 契约⑦⑧：三态化（system/light/dark）；静态 palette/onAccent
// 历史导出随投影字段一并删除——组件一律经 useAppTheme()/useAccent()/useOnAccent() 取派生值。
import { createContext, useContext, useMemo, createElement, type ReactNode } from 'react';
import { useColorScheme } from 'react-native';
import {
  ACCENTS,
  ACCENT_ORDER,
  ON_ACCENT,
  DARK_NEUTRALS,
  resolvePalette,
  resolveThemeMode,
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

// 主题派生值（含完整 NeutralPalette + 当前主题模式）。
export interface AppThemeValue {
  theme: Theme;
  palette: NeutralPalette;
}

// 缺省值 = dark（与历史静态 palette 同源；未包 ThemeProvider 时组件仍可安全渲染）。
const DARK_THEME_VALUE: AppThemeValue = { theme: 'dark', palette: DARK_NEUTRALS };

const ThemeContext = createContext<AppThemeValue>(DARK_THEME_VALUE);

/** 主题 Provider：消费 core preferences.mode（useStore）+ useColorScheme 解析 system 三态，
 *  经 core resolvePalette 派生 palette（dark→DARK_NEUTRALS / light→LIGHT_NEUTRALS）。
 *  （.ts 文件用 createElement，保持既有 `../theme` 导入路径不变。） */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const preferences = useStore((s) => s.state.settings.preferences);
  // system 三态解析（THEME-3 契约⑦）：useColorScheme 随系统切换即时重渲染，手动 light/dark 脱离 system。
  // 宿主降级口径：宿主未提供 useColorScheme 导出（部分测试替身的 react-native mock 等）时不抛错，
  // 按无系统偏好解析（显式 light/dark 不受影响；system 档降级为 light），渲染不阻塞。
  let systemPrefersDark = false;
  try {
    systemPrefersDark = useColorScheme() === 'dark';
  } catch {
    systemPrefersDark = false;
  }
  const theme = resolveThemeMode(preferences.mode, systemPrefersDark);
  const value = useMemo<AppThemeValue>(() => ({ theme, palette: resolvePalette(theme) }), [theme]);
  return createElement(ThemeContext.Provider, { value }, children);
}

/** 当前主题派生值（palette + theme 模式）。UI 样式一律由此取中性色。 */
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
