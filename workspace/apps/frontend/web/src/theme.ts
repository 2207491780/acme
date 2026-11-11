import { theme as antdTheme, type ThemeConfig } from 'antd';
import {
  ACCENTS,
  ON_ACCENT,
  TONES,
  DENSITY,
  FONT_FAMILY,
  resolvePalette,
  withAlpha,
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