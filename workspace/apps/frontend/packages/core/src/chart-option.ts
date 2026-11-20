import { mixHex, withAlpha } from './theme';

/**
 * 图表 headless 配置工厂（REQ-20261114-120112，AC-1/AC-2）：
 * 主题快照 → echarts option。core 层不含 echarts 依赖（web 层懒加载渲染），
 * 全部色值一律经 theme.ts 的 withAlpha / mixHex 派生管线产出，禁止硬编码色值；
 * fontSize 直接承载快照密度，随主题切换整体刷新。
 */

export type ChartThemeMode = 'light' | 'dark';

/** 主题快照（只承载令牌值；工厂只消费快照，不得自带常量色）。 */
export interface ChartThemeSnapshot {
  mode: ChartThemeMode;
  colors: {
    accent: string;
    text: string;
    subText: string;
    border: string;
    background: string;
    surface: string;
    /** 语义色生效值（REQ-20261121-043502 THEME-2：overrides→TONES 回退，图表联动）；
     *  旧装配快照可缺省（工厂当前按 accent/中性色派生，语义色供状态系列扩展消费）。 */
    success?: string;
    warning?: string;
    danger?: string;
  };
  fontSize: number;
}

export interface TrendChartData {
  labels: string[];
  values: number[];
}

export interface DistributionItem {
  name: string;
  value: number;
}

export interface DistributionChartData {
  items: DistributionItem[];
}

export type ChartKind = 'trend' | 'distribution';

/** 与派生空间对齐：α/r 均收敛到两位小数（k/100），保证落在枚举出的合法派生色空间内。 */
const quantize = (v: number): number => Math.round(v * 100) / 100;

const HEX_RE = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;

const isHex = (value: string): boolean => HEX_RE.test(value);

/** withAlpha 的安全面：非 hex 载体（如令牌的 rgba 边框）原样透传（仍为令牌值，不硬编码）。 */
const alphaOf = (base: string, alpha: number): string =>
  isHex(base) ? withAlpha(base, quantize(alpha)) : base;

/** mixHex 的安全面：任一端非 hex 时退回首端原值。 */
const mixOf = (a: string, b: string, ratio: number): string =>
  isHex(a) && isHex(b) ? mixHex(a, b, quantize(ratio)) : a;

/** 分布图扇区色：强调色与快照内中性基色的同族低饱和混色（克制的层次，不做强对比撞色）。 */
function pieColor(theme: ChartThemeSnapshot, index: number): string {
  if (index <= 0) {
    return theme.colors.accent;
  }
  const targets = [theme.colors.subText, theme.colors.border, theme.colors.background, theme.colors.text, theme.colors.surface];
  const ratios = [0.75, 0.62, 0.5, 0.38, 0.26];
  const i = index - 1;
  return mixOf(theme.colors.accent, targets[i % targets.length], ratios[i % ratios.length]);
}

function trendOption(data: TrendChartData, theme: ChartThemeSnapshot): Record<string, unknown> {
  const fs = theme.fontSize;
  const sub = fs - 1;
  return {
    textStyle: { color: theme.colors.subText, fontSize: sub },
    grid: { left: 48, right: 24, top: 40, bottom: 32, containLabel: true },
    tooltip: { trigger: 'axis', textStyle: { color: theme.colors.text, fontSize: fs } },
    xAxis: {
      type: 'category',
      data: data.labels,
      boundaryGap: false,
      axisLine: { lineStyle: { color: theme.colors.border } },
      axisLabel: { color: theme.colors.subText, fontSize: sub },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: theme.colors.subText, fontSize: sub },
      splitLine: { lineStyle: { color: alphaOf(theme.colors.border, 0.6) } },
    },
    series: [
      {
        type: 'line',
        data: data.values,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        itemStyle: { color: theme.colors.accent, borderColor: theme.colors.surface, borderWidth: 1 },
        lineStyle: { color: theme.colors.accent, width: 2 },
        areaStyle: { color: alphaOf(theme.colors.accent, 0.16) },
      },
    ],
  };
}

function distributionOption(data: DistributionChartData, theme: ChartThemeSnapshot): Record<string, unknown> {
  const fs = theme.fontSize;
  const sub = fs - 1;
  return {
    textStyle: { color: theme.colors.subText, fontSize: sub },
    tooltip: { trigger: 'item', textStyle: { color: theme.colors.text, fontSize: fs } },
    legend: { bottom: 0, icon: 'circle', textStyle: { color: theme.colors.subText, fontSize: sub } },
    series: [
      {
        type: 'pie',
        radius: ['46%', '70%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: theme.colors.surface, borderWidth: 2 },
        label: { color: theme.colors.text, fontSize: sub },
        labelLine: { lineStyle: { color: theme.colors.border } },
        data: data.items.map((item, i) => ({
          name: item.name,
          value: item.value,
          itemStyle: { color: pieColor(theme, i) },
        })),
      },
    ],
  };
}

/** kind='trend'：{ labels, values } → 折线趋势；kind='distribution'：{ items } → 饼图分布。 */
export function createChartOption(
  kind: ChartKind,
  data: TrendChartData | DistributionChartData,
  theme: ChartThemeSnapshot,
): Record<string, unknown> {
  switch (kind) {
    case 'trend':
      return trendOption(data as TrendChartData, theme);
    case 'distribution':
      return distributionOption(data as DistributionChartData, theme);
    default:
      throw new Error(`createChartOption: 未知的图表类型 ${String(kind)}`);
  }
}
