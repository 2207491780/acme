import { theme as antdTheme, type ThemeConfig } from 'antd';
import {
  ACCENTS,
  DENSITY,
  FONT_FAMILY,
  FONT_SIZE,
  resolveAccentColor,
  resolveChromePalette,
  resolveOnAccent,
  resolvePalette,
  resolveRadiusTokenSet,
  resolveSemanticColors,
  resolveThemeMode,
  TONES,
  withAlpha,
  type ChartThemeSnapshot,
  type Settings,
} from '@acme/core';

export { ACCENTS, ACCENT_ORDER } from '@acme/core';

/**
 * mode=system 的系统深浅色读取（web 层职责）：显式注入值优先（main.tsx 经监听 hook 注入，
 * 系统切换即重渲染）；未注入时调用点即时读 matchMedia（契约钉死消费点 buildTheme(settings)
 * 单参调用亦能正确解析 system，如测试装配）。环境无 matchMedia（node 桩）安全降级 false。
 */
function readSystemPrefersDark(): boolean {
  try {
    return typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
}

function resolveMode(p: Settings['preferences'], systemPrefersDark?: boolean): 'light' | 'dark' {
  if (p.mode !== 'system') return p.mode;
  return resolveThemeMode('system', systemPrefersDark ?? readSystemPrefersDark());
}

/**
 * REQ-20261121-010725：settings.preferences 为唯一事实源——模式经 resolveThemeMode 解析
 * （system 优先取注入值，缺省即时读 matchMedia）、强调色经 resolveAccentColor 解析
 * （customAccent 优先）。
 * REQ-20261121-043502（THEME-2）：语义色经 resolveSemanticColors（overrides→TONES 回退）；
 * 圆角档位 radius=medium（默认）保持现状（密度基线 DENSITY[density].radius，LG/SM 仍由
 * AntD 算法派生不动），显式档位 small/large/full 覆盖 borderRadius 并按 RADII 比例联动
 * SM/LG（AntD genRadius 对 LG≥16 封顶，需显式注入才能突破，见 core resolveRadiusTokens）。
 */
export function buildTheme(settings: Settings, systemPrefersDark?: boolean): ThemeConfig {
  const p = settings.preferences;
  const mode = resolveMode(p, systemPrefersDark);
  const dark = mode === 'dark';
  const accent = resolveAccentColor(p);
  const n = resolvePalette(mode);
  const d = DENSITY[p.density];
  const semantic = resolveSemanticColors(p.semanticColors);
  const radiusScale = p.radius ?? 'medium';
  const radiusToken =
    radiusScale === 'medium' ? { borderRadius: d.radius } : resolveRadiusTokenSet(radiusScale);
  // 半深色（REQ-20261122-233427 确认⑤）：浅色模式 + siderTone=dark 时侧栏/顶栏取暗色中性
  // 盘子派生（亮页面 × 深双栏）；暗色模式下无效（chrome=页面同源盘子，与现状逐字一致）。
  const halfDark = mode === 'light' && p.siderTone === 'dark';
  const chrome = resolveChromePalette(mode, p.siderTone);
  return {
    algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: accent,
      colorInfo: accent,
      // 语义色和 purple 种子统一取 core，避免预设标签回落到 AntD 高饱和配色。
      colorSuccess: semantic.success,
      colorWarning: semantic.warning,
      colorError: semantic.danger,
      purple: TONES.violet,
      colorTextLightSolid: resolveOnAccent(p),
      ...radiusToken,
      fontFamily: FONT_FAMILY.sans,
      ...(dark
        ? {
            colorBgBase: n.bg,
            colorBgLayout: n.bg,
            colorBgContainer: n.surface,
            colorBgElevated: n.elevated,
            colorText: n.text,
            colorTextSecondary: n.text2,
            colorBorder: n.border,
            colorBorderSecondary: n.borderSecondary,
          }
        : {}),
    },
    components: {
      Layout: {
        headerHeight: 64,
        headerPadding: '0 24px',
        bodyBg: 'transparent',
        // 半深色注入面（确认⑤）：非半深色保持 transparent（global.css 玻璃变量接管，现状不变）
        siderBg: halfDark ? chrome.sider.glassSider : 'transparent',
        headerBg: halfDark ? chrome.header.glassHeader : 'transparent',
      },
      Menu: {
        itemBg: 'transparent',
        subMenuItemBg: 'transparent',
        itemSelectedBg: `${accent}29`,
        itemSelectedColor: accent,
      },
      Table: {
        headerBg: n.elevated,
        rowHoverBg: withAlpha(dark ? ACCENTS.cyan : ACCENTS.blue, 0.06),
        rowSelectedBg: `${accent}1f`,
        rowSelectedHoverBg: `${accent}29`,
        cellPaddingBlock: d.cellBlock,
        cellPaddingInline: d.cellInline,
      },
      Card: { paddingLG: d.cardPadding },
    },
  };
}

/**
 * 图表主题快照（REQ-20261114-120112，AC-2）：从既有 settings 派生 createChartOption
 * 所需的快照形状——全部色值取自 core 令牌（ACCENTS / resolvePalette），字号随密度单调。
 * mode 经 resolveThemeMode 解析（system → 按注入的系统深浅色落为 light/dark）。
 * THEME-2（REQ-20261121-043502）：语义色随 resolveSemanticColors 同步进快照（overrides→
 * TONES 回退），图表状态系列与 AntD colorSuccess/Warning/Error 同源联动。
 */
export function themeSnapshot(settings: Settings, systemPrefersDark?: boolean): ChartThemeSnapshot {
  const p = settings.preferences;
  const mode = resolveMode(p, systemPrefersDark);
  const n = resolvePalette(mode);
  const semantic = resolveSemanticColors(p.semanticColors);
  return {
    mode,
    colors: {
      accent: resolveAccentColor(p),
      text: n.text,
      subText: n.text2,
      border: n.border,
      background: n.bg,
      surface: n.surface,
      success: semantic.success,
      warning: semantic.warning,
      danger: semantic.danger,
    },
    fontSize: p.density === 'compact' ? FONT_SIZE.small : FONT_SIZE.base,
  };
}
