import type { Accent, Density, Env, Health, Platform, RadiusScale, Status, Theme, Tone } from './types';
import { clamp, statusTone } from './utils';

// 设计令牌唯一事实源。Web(Ant Design) 与 Mobile(React Native) 主题均从此派生，
// 禁止在组件或样式表里另行硬编码这些色值/尺寸；新增令牌先改这里再派生。
// 视觉基调：耐看、简约、低饱和——克制的高级灰中性色 + 去饱和的柔和强调色，
// 不使用霓虹/光晕/彩虹渐变；渐变只在同色系内做极轻微的明度变化。

export const ACCENTS: Record<Accent, string> = {
  cyan: '#5aa7a0',
  blue: '#6f92c4',
  violet: '#9188c9',
  magenta: '#bd7ba0',
  amber: '#cfa14f',
  acid: '#9db56a',
  // REQ-20261121-010725：预设由 6 扩至 12（低饱和近代感色系，命名/色值 TL 定稿）
  emerald: '#58aa82',
  sky: '#6aa8cf',
  coral: '#cd7a63',
  ruby: '#c56d6d',
  bronze: '#b08968',
  slate: '#8a9bb0',
};

export const ACCENT_ORDER: Accent[] = [
  'cyan',
  'blue',
  'violet',
  'magenta',
  'amber',
  'acid',
  'emerald',
  'sky',
  'coral',
  'ruby',
  'bronze',
  'slate',
];

// 实心强调色之上的前景（文字/图标）。强调色均为中间明度，统一用纯黑前景，
// 保证暗色算法把主色压暗后仍 ≥4.5:1（最暗的 blue 在 Web 暗色下约 5.1:1）。
export const ON_ACCENT: Record<Accent, string> = {
  cyan: '#000000',
  blue: '#000000',
  violet: '#000000',
  magenta: '#000000',
  amber: '#000000',
  acid: '#000000',
  emerald: '#000000',
  sky: '#000000',
  coral: '#000000',
  ruby: '#000000',
  bronze: '#000000',
  slate: '#000000',
};

// 渐变伴随色（accent-2 / accent-3）：同族去饱和色，仅用于极轻微的层次，不做强对比撞色。
// 新增 6 色沿用既有惯例：从现有 accent hex 交叉取色（同族去饱和）。
export const ACCENT_COMPANIONS: Record<Accent, { secondary: string; tertiary: string }> = {
  cyan: { secondary: '#6f92c4', tertiary: '#9188c9' },
  blue: { secondary: '#5aa7a0', tertiary: '#9188c9' },
  violet: { secondary: '#bd7ba0', tertiary: '#5aa7a0' },
  magenta: { secondary: '#cfa14f', tertiary: '#6f92c4' },
  amber: { secondary: '#9db56a', tertiary: '#5aa7a0' },
  acid: { secondary: '#6fa287', tertiary: '#6f92c4' },
  emerald: { secondary: '#9db56a', tertiary: '#5aa7a0' },
  sky: { secondary: '#6f92c4', tertiary: '#5aa7a0' },
  coral: { secondary: '#c56d6d', tertiary: '#cfa14f' },
  ruby: { secondary: '#cd7a63', tertiary: '#bd7ba0' },
  bronze: { secondary: '#cfa14f', tertiary: '#cd7a63' },
  slate: { secondary: '#6f92c4', tertiary: '#6aa8cf' },
};

// 非可选的扩展品牌色，仅用于 Web 图标/状态点缀（去饱和）。
export const BRAND_HUES = {
  teal: '#6fa287',
  red: '#c47b7b',
  slate: '#8b929e',
} as const;

// 语义色规范值（Mobile 直接渲染这些 hex）。低饱和，长时间阅读不刺眼。
export const TONES: Record<Tone, string> = {
  muted: '#8b929e',
  info: '#6f92c4',
  violet: '#9188c9',
  warning: '#cfa14f',
  success: '#6fa287',
  danger: '#c47b7b',
};

// Web 把同一语义映射到 Ant Design 预设色名，由 AntD 算法按主题渲染，
// 因此 hex 与 Mobile 略有差异，但语义一致——这是有意的平台 idiomatic 渲染。
export const TONE_ANTD: Record<Tone, string> = {
  muted: 'default',
  info: 'processing',
  violet: 'purple',
  warning: 'warning',
  success: 'success',
  danger: 'error',
};

export const PLATFORM_COLORS: Record<Platform, string> = {
  web: '#5aa7a0',
  desktop: '#9188c9',
  mobile: '#6fa287',
};

export const PLATFORM_ANTD: Record<Platform, string> = {
  web: 'processing',
  desktop: 'purple',
  mobile: 'success',
};

export const ENV_COLORS: Record<Env, string> = {
  dev: '#8b929e',
  test: '#cfa14f',
  prod: '#c47b7b',
};

export const ENV_ANTD: Record<Env, string> = {
  dev: 'default',
  test: 'warning',
  prod: 'error',
};

export const HEALTH_COLORS: Record<Health, string> = {
  healthy: '#6fa287',
  warning: '#cfa14f',
  critical: '#c47b7b',
};

export const HEALTH_ANTD: Record<Health, string> = {
  healthy: 'success',
  warning: 'warning',
  critical: 'error',
};

export const HEALTH_LABELS: Record<Health, string> = {
  healthy: '健康',
  warning: '关注',
  critical: '异常',
};

export interface NeutralPalette {
  bg: string;
  bg1: string;
  surface: string;
  elevated: string;
  surface3: string;
  text: string;
  text2: string;
  muted: string;
  border: string;
  borderSecondary: string;
  glassSider: string;
  glassHeader: string;
  scrim: string;
}

// 中性色为暖调高级灰（不带蓝紫偏色），发丝级描边，柔和阴影。
export const DARK_NEUTRALS: NeutralPalette = {
  bg: '#14161a',
  bg1: '#191c21',
  surface: '#1d2127',
  elevated: '#242932',
  surface3: '#2c323c',
  text: '#e8eaed',
  text2: '#b6bcc6',
  muted: '#8b929e',
  border: 'rgba(255, 255, 255, 0.08)',
  borderSecondary: 'rgba(255, 255, 255, 0.05)',
  glassSider: 'rgba(29, 33, 39, 0.85)',
  glassHeader: 'rgba(20, 22, 26, 0.8)',
  scrim: 'rgba(0, 0, 0, 0.4)',
};

export const LIGHT_NEUTRALS: NeutralPalette = {
  bg: '#f7f8f9',
  bg1: '#f1f2f4',
  surface: '#ffffff',
  elevated: '#f4f5f7',
  surface3: '#e9ebee',
  text: '#1c2128',
  text2: '#4a5261',
  muted: '#7a8290',
  border: 'rgba(20, 24, 32, 0.10)',
  borderSecondary: 'rgba(20, 24, 32, 0.06)',
  glassSider: 'rgba(255, 255, 255, 0.85)',
  glassHeader: 'rgba(247, 248, 249, 0.8)',
  scrim: 'rgba(20, 24, 32, 0.25)',
};

export function resolvePalette(theme: Theme): NeutralPalette {
  return theme === 'light' ? LIGHT_NEUTRALS : DARK_NEUTRALS;
}

export const SPACING = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32, xxxl: 48 } as const;

export const RADII = { sm: 8, md: 12, lg: 16, xl: 20, pill: 999 } as const;

export const FONT_FAMILY = {
  sans: '"Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
  mono: '"Cascadia Code", "JetBrains Mono", Consolas, monospace',
} as const;

export const FONT_SIZE = {
  caption: 11,
  small: 12,
  body: 13,
  base: 14,
  lead: 16,
  title: 20,
  h2: 24,
  h1: 30,
  display: 38,
} as const;

export const FONT_WEIGHT = { regular: 400, medium: 500, semibold: 600, bold: 700, heavy: 800 } as const;

export const LINE_HEIGHT = { tight: 1.1, snug: 1.3, normal: 1.5, relaxed: 1.7 } as const;

export const LETTER_SPACING = { eyebrow: 2.4, brand: 1.6, tight: -0.6 } as const;

export const MOTION = {
  fast: 120,
  base: 200,
  slow: 320,
  tick: 1200,
  easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
} as const;

// 柔和、低透明度的投影，避免厚重的霓虹光晕。
export const SHADOWS = {
  card: '0 8px 24px rgba(0, 0, 0, 0.16)',
  pop: '0 4px 12px rgba(0, 0, 0, 0.12)',
} as const;

export const Z = { base: 0, sticky: 20, nav: 30, drawer: 1000, modal: 1000, toast: 1010 } as const;

// 密度预设：数值与 Web AntD 主题逐项对应（圆角/卡片内边距/表格单元格）。
export const DENSITY: Record<Density, { radius: number; cardPadding: number; cellBlock: number; cellInline: number; gap: number }> = {
  comfortable: { radius: RADII.md, cardPadding: 20, cellBlock: 13, cellInline: 14, gap: SPACING.lg },
  compact: { radius: RADII.sm, cardPadding: 16, cellBlock: 8, cellInline: 10, gap: SPACING.md },
};

// —— 外观能力包（REQ-20261121-043502 THEME-2）——

/** 语义色覆盖键（THEME-2 确认⑤）：仅 success/warning/danger 可覆盖，info/muted/violet 保持体系稳定。 */
export type SemanticColorKey = 'success' | 'warning' | 'danger';

/** 语义色覆盖（preferences v3 内嵌形状）：null = 用 TONES 基线；hex = #RRGGBB 自定义覆盖值。 */
export interface SemanticColorOverrides {
  success: string | null;
  warning: string | null;
  danger: string | null;
}

/** 圆角档位 → AntD token 三件套（THEME-2 确认③）：borderRadius 主值 + SM/LG 按 RADII 现值比例联动。 */
export interface RadiusTokenSet {
  borderRadius: number;
  borderRadiusSM: number;
  borderRadiusLG: number;
}

// 圆角档位→RADII 缩放系数（确认③：small≈0.8×、medium=基线不动、large≈1.4×）。
const RADIUS_SCALE_FACTOR: Record<RadiusScale, number> = {
  small: 0.8,
  medium: 1,
  large: 1.4,
  full: 1,
};

/**
 * 圆角档位映射（THEME-2 确认③）→ AntD token.borderRadius 数值，按 RADII 现值推导取整：
 * - small = RADII.md×0.8 = 9.6→10；medium = RADII.md = 12（现状基线不动，= DENSITY.comfortable.radius）；
 * - large = RADII.md×1.4 = 16.8→17；full = RADII.pill = 999（胶囊统一大圆角，档位上界）。
 */
export function resolveRadiusTokens(radius: RadiusScale): number {
  if (radius === 'full') return RADII.pill;
  return Math.round(RADII.md * RADIUS_SCALE_FACTOR[radius]);
}

/**
 * 圆角档位 → AntD borderRadius/SM/LG 三件套（THEME-2 确认③「components 相关联」）：
 * SM/LG 按 RADII 现值比例联动（sm/md=2/3、lg/md=4/3 取整；small→7/13、large→11/23）；
 * full 三值同步 999——AntD genRadius 会把 LG 封顶在 16，胶囊容器圆角须显式注入突破。
 */
export function resolveRadiusTokenSet(radius: RadiusScale): RadiusTokenSet {
  const value = resolveRadiusTokens(radius);
  if (value >= RADII.pill) {
    return { borderRadius: RADII.pill, borderRadiusSM: RADII.pill, borderRadiusLG: RADII.pill };
  }
  return {
    borderRadius: value,
    borderRadiusSM: Math.round(value * (RADII.sm / RADII.md)),
    borderRadiusLG: Math.round(value * (RADII.lg / RADII.md)),
  };
}

/**
 * 语义色预设档（THEME-2 确认⑤）：每语义 2 预设 = TONES 基线 + 邻近近似档。
 * 邻近档从基线 hex 同族派生（向黑混 14%，保持去饱和基调、仅轻微明度变化：
 * success→#5f8b74 / warning→#b28a44 / danger→#a96a6a），不引入新的色系。
 */
export const SEMANTIC_PRESETS: Record<SemanticColorKey, [string, string]> = {
  success: [TONES.success, mixHex(TONES.success, '#000000', 0.86)],
  warning: [TONES.warning, mixHex(TONES.warning, '#000000', 0.86)],
  danger: [TONES.danger, mixHex(TONES.danger, '#000000', 0.86)],
};

// 与 preferences.isHexColor / chart-option HEX_RE 同口径：3/6 位 hex 才视为合法覆盖值。
const SEMANTIC_HEX_RE = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;

/**
 * 语义色解析（THEME-2 确认⑤）：null/非法值→TONES 基线回退；合法 hex（3/6 位归一化为
 * 小写 6 位）→覆盖。经 validateThemePreferences 归一化后 overrides 已合法，此处再校验是
 * 兜底旧装配/未过校验的直接调用（resolveAccentColor 同款防御口径）。
 */
export function resolveSemanticColors(
  overrides: SemanticColorOverrides | null | undefined,
): Record<SemanticColorKey, string> {
  const resolve = (key: SemanticColorKey): string => {
    const value = overrides?.[key];
    if (typeof value !== 'string' || !SEMANTIC_HEX_RE.test(value)) return TONES[key];
    const h = value.replace('#', '').toLowerCase();
    return `#${h.length === 3 ? h.split('').map((c) => c + c).join('') : h}`;
  };
  return { success: resolve('success'), warning: resolve('warning'), danger: resolve('danger') };
}

function parseHex(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
  return [parseInt(full.slice(0, 2), 16), parseInt(full.slice(2, 4), 16), parseInt(full.slice(4, 6), 16)];
}

function toHex(r: number, g: number, b: number): string {
  const c = (n: number) => clamp(Math.round(n), 0, 255).toString(16).padStart(2, '0');
  return `#${c(r)}${c(g)}${c(b)}`;
}

// hex -> rgba 字符串，用于半透明叠加（选中态、悬停态）。
export function withAlpha(hex: string, alpha: number): string {
  const [r, g, b] = parseHex(hex);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

// 模拟 CSS color-mix(in srgb, a weight%, b)，weight 为 a 的占比。
export function mixHex(a: string, b: string, weight: number): string {
  const pa = parseHex(a);
  const pb = parseHex(b);
  const w = clamp(weight, 0, 1);
  const ch = (i: number) => pa[i] * w + pb[i] * (1 - w);
  return toHex(ch(0), ch(1), ch(2));
}

export function statusColor(status: Status | string): string {
  return TONES[statusTone(status)];
}

// 渐变配方：克制、同色系、低对比。brand 仅做轻微明度变化；hero 极淡染色淡出到表面色；
// text 在强调色与同族伴随色间做柔和过渡。Mobile 以纯色/近似呈现（无渐变依赖）。
export const GRADIENTS = {
  brand: (accent: Accent): string => {
    return `linear-gradient(135deg, ${ACCENTS[accent]}, ${mixHex(ACCENTS[accent], '#000000', 0.82)})`;
  },
  hero: (accent: Accent, theme: Theme = 'dark'): string => {
    const n = resolvePalette(theme);
    return `linear-gradient(160deg, ${mixHex(ACCENTS[accent], n.surface, 0.06)}, ${n.surface})`;
  },
  text: (accent: Accent): string => {
    const c = ACCENT_COMPANIONS[accent];
    return `linear-gradient(120deg, ${ACCENTS[accent]}, ${c.secondary})`;
  },
};