import { theme as antdTheme, type ThemeConfig } from 'antd';
import {
  ACCENTS,
  ON_ACCENT,
  TONES,
  DENSITY,
  FONT_FAMILY,
  FONT_SIZE,
  resolvePalette,
  withAlpha,
  type ChartThemeSnapshot,
  type Settings,
} from '@acme/core';

export { ACCENTS, ACCENT_ORDER } from '@acme/core';

export function buildTheme(settings: Settings): ThemeConfig {
  const dark = settings.theme === 'dark';
  const accent = ACCENTS[settings.accent];
  const n = resolvePalette(settings.theme);
  const d = DENSITY[settings.density];
  return {
    algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: accent,
      colorInfo: accent,
      // 语义色和 purple 种子统一取 core，避免预设标签回落到 AntD 高饱和配色。
      colorSuccess: TONES.success,
      colorWarning: TONES.warning,
      colorError: TONES.danger,
      purple: TONES.violet,
      colorTextLightSolid: ON_ACCENT[settings.accent],
      borderRadius: d.radius,
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
        siderBg: 'transparent',
        headerBg: 'transparent',
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
 */
export function themeSnapshot(settings: Settings): ChartThemeSnapshot {
  const n = resolvePalette(settings.theme);
  return {
    mode: settings.theme,
    colors: {
      accent: ACCENTS[settings.accent],
      text: n.text,
      subText: n.text2,
      border: n.border,
      background: n.bg,
      surface: n.surface,
    },
    fontSize: settings.density === 'compact' ? FONT_SIZE.small : FONT_SIZE.base,
  };
}