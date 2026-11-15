/** @vitest-environment jsdom */
/**
 * REQ-20261114-120112 AC-1/AC-2：图表 headless 配置工厂（主题令牌→echarts option，core 层）。
 *
 *   AC-1 至少 2 类图表（趋势/分布）：工厂按 kind 产出 line/pie 两类 option（数据衍生与
 *        空态/加载态在 web 层 REQ20261114120112-chart.test.tsx 覆盖）。
 *   AC-2 配色随主题令牌刷新、无硬编码色值：注入主题快照（accent/暗色/密度）→ option 内
 *        全部色值必须落在「快照基色 × withAlpha/mixHex 全量派生空间」内（契约背景节点名
 *        派生管线）；accent/暗色/密度任一变化后 option 相应刷新。
 *
 * 装配口径：core barrel '../src'（与 REQ20261114120103-apiFetch 同族；barrel 拉 persist 型
 * store 需真实 localStorage → jsdom docblock）；纯 headless 断言，不渲染。
 *
 * 【表面假定（REQ20261114120112 套件内连续编号）】
 *   A-1 工厂表面：core barrel 命名导出 createChartOption(kind, data, theme)；
 *       kind='trend'|'distribution'；trend 数据形状 { labels: string[]; values: number[] }，
 *       distribution 数据形状 { items: { name: string; value: number }[] }。契约仅约定
 *       「主题令牌→echarts option、趋势/分布两类」，参数形状未契约化 → TL 门禁对齐。
 *   A-2 派生管线表面：core barrel 导出 withAlpha(hex, alpha) / mixHex(a, b, ratio)
 *       （契约背景点名的派生管线），本文件用其构造「合法派生色空间」（α/r 按 0.01 步长
 *       全量枚举；若实现使用更高精度 alpha 或第三方混色函数，本组失败即上报口径）。
 *   A-3 主题快照形状：{ mode: 'light'|'dark', colors: { accent, text, subText, border,
 *       background, surface }, fontSize }（快照承载令牌值，工厂只消费快照、不得自带常量色）。
 *   A-4 密度映射：快照 fontSize 单调反映到 option 的文字 fontSize（用单调性断言，兼容
 *       缩放系数实现）。
 */
import { describe, expect, it } from 'vitest';
import { createChartOption, mixHex, withAlpha } from '../src';

// ————— 主题快照 fixture（A-3；基色取非常见值，降低与潜在硬编码默认色撞车的概率） —————
type ThemeSnapshot = {
  mode: 'light' | 'dark';
  colors: {
    accent: string; text: string; subText: string;
    border: string; background: string; surface: string;
  };
  fontSize: number;
};

const LIGHT: ThemeSnapshot = {
  mode: 'light',
  colors: {
    accent: '#B62A38', text: '#1F2329', subText: '#646A73',
    border: '#DEE0E3', background: '#F5F6F7', surface: '#FFFFFF',
  },
  fontSize: 12,
};
const ACCENT_B = '#0F7B6C';
const LIGHT_ACCENT_B: ThemeSnapshot = {
  ...LIGHT, colors: { ...LIGHT.colors, accent: ACCENT_B },
};
const DARK: ThemeSnapshot = {
  mode: 'dark',
  colors: {
    accent: LIGHT.colors.accent, text: '#DFE1E5', subText: '#9AA0A6',
    border: '#3A3F44', background: '#171A1C', surface: '#22262A',
  },
  fontSize: LIGHT.fontSize,
};
const DENSE: ThemeSnapshot = { ...LIGHT, fontSize: 14 };

// ————— 容差调用面（A-1：签名形状未契约化，运行时按位置参数透传） —————
type AnyOption = Record<string, unknown>;
const build = (kind: string, data: unknown, theme: unknown): AnyOption =>
  (createChartOption as unknown as (...args: unknown[]) => AnyOption)(kind, data, theme);

const TREND_DATA = { labels: ['周一', '周二', '周三', '周四', '周五'], values: [3, 7, 4, 9, 6] };
const PIE_DATA = {
  items: [
    { name: '发布单', value: 12 },
    { name: '需求', value: 8 },
  ],
};

// ————— 色值收集与派生空间（A-2） —————
const COLOR_RE = /#(?:[0-9a-fA-F]{3,8})|rgba?\([^)]*\)/g;

const hex3 = (hex: string) => hex.split('').map((c) => c + c).join('');

/** 归一化为 `rgb(r,g,b)|alpha`（兼容 #RGB/#RRGGBB/#RRGGBBAA/rgb()/rgba() 五种载体）。 */
const parseColor = (raw: string): string | null => {
  const s = raw.trim().toLowerCase();
  if (s.startsWith('#')) {
    const hex = s.slice(1);
    if (hex.length !== 3 && hex.length !== 6 && hex.length !== 8) return null;
    const full = hex.length === 3 ? hex3(hex) : hex;
    const r = parseInt(full.slice(0, 2), 16);
    const g = parseInt(full.slice(2, 4), 16);
    const b = parseInt(full.slice(4, 6), 16);
    if ([r, g, b].some(Number.isNaN)) return null;
    const a = full.length === 8 ? parseInt(full.slice(6, 8), 16) / 255 : 1;
    return `rgb(${r},${g},${b})|${a.toFixed(2)}`;
  }
  const m = s.match(/^rgba?\(([^)]+)\)$/);
  if (!m) return null;
  const parts = m[1].split(/[\s,/]+/).filter(Boolean).map(Number);
  const [r, g, b, a = 1] = parts;
  if ([r, g, b].some(Number.isNaN) || Number.isNaN(a)) return null;
  return `rgb(${r},${g},${b})|${Number(a).toFixed(2)}`;
};

const colorsOf = (value: unknown): Set<string> => {
  const out = new Set<string>();
  for (const raw of JSON.stringify(value).match(COLOR_RE) ?? []) {
    const norm = parseColor(raw);
    if (norm) out.add(norm);
  }
  return out;
};

const tryDerive = (fn: () => unknown): string | null => {
  try {
    return parseColor(String(fn()));
  } catch {
    return null; // 参数顺序/形状不合时跳过（A-2 容差）
  }
};

/** 合法派生色空间：基色本身 ∪ withAlpha(α∈[0,1] step .01) ∪ mixHex 双向(r∈[0,1] step .01)。 */
const derivationSpace = (theme: ThemeSnapshot): Set<string> => {
  const bases = Object.values(theme.colors);
  const space = new Set<string>();
  for (const c of bases) {
    const cc = parseColor(c);
    if (cc) space.add(cc);
    for (let i = 0; i <= 100; i += 1) {
      const a = tryDerive(() => withAlpha(c, i / 100));
      if (a) space.add(a);
      const r1 = tryDerive(() => withAlpha(i / 100, c)); // 参数序容差
      if (r1) space.add(r1);
    }
  }
  for (const a of bases) {
    for (const b of bases) {
      for (let i = 0; i <= 100; i += 1) {
        const m1 = tryDerive(() => mixHex(a, b, i / 100));
        if (m1) space.add(m1);
        const m2 = tryDerive(() => mixHex(b, a, i / 100)); // 参数序容差
        if (m2) space.add(m2);
      }
    }
  }
  return space;
};

// ————— 结构遍历辅助 —————
const seriesList = (option: AnyOption): Array<Record<string, unknown>> => {
  const s = option.series;
  if (Array.isArray(s)) return s as Array<Record<string, unknown>>;
  return s ? [s as Record<string, unknown>] : [];
};
const seriesTypes = (option: AnyOption) => seriesList(option).map((s) => s?.type).filter(Boolean);

const fontSizesOf = (option: unknown): number[] => {
  const out: number[] = [];
  const walk = (node: unknown) => {
    if (Array.isArray(node)) { node.forEach(walk); return; }
    if (node && typeof node === 'object') {
      for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
        if (k === 'fontSize' && typeof v === 'number') out.push(v);
        else walk(v);
      }
    }
  };
  walk(option);
  return out;
};

const axisCategories = (option: AnyOption): unknown[] => {
  const ax = option.xAxis;
  const node = Array.isArray(ax) ? ax[0] : ax;
  return ((node ?? {}) as Record<string, unknown>).data as unknown[] ?? [];
};

// ————— AC-1：两类图表（趋势/分布） —————
describe('REQ20261114120112 图表 option 工厂 两类图表（AC-1）', () => {
  it('should_produce_trend_line_option_when_kind_trend', () => {
    const option = build('trend', TREND_DATA, LIGHT);
    expect(seriesTypes(option)).toContain('line');
    expect(axisCategories(option)).toEqual(TREND_DATA.labels);
  });

  it('should_produce_distribution_pie_option_when_kind_distribution', () => {
    const option = build('distribution', PIE_DATA, LIGHT);
    expect(seriesTypes(option)).toContain('pie');
    const pie = seriesList(option).find((s) => s.type === 'pie') as { data?: Array<{ name?: string }> };
    expect((pie.data ?? []).map((d) => d?.name)).toEqual(PIE_DATA.items.map((i) => i.name));
  });
});

// ————— AC-2：配色=主题令牌派生、随主题刷新 —————
describe('REQ20261114120112 图表 option 工厂 主题令牌派生（AC-2）', () => {
  it('should_derive_all_colors_from_theme_tokens_when_build_option', () => {
    const option = build('trend', TREND_DATA, LIGHT);
    const colors = colorsOf(option);
    expect(colors.size).toBeGreaterThan(0); // 派生管线确实注入了配色
    const space = derivationSpace(LIGHT);
    for (const c of colors) {
      expect(space.has(c), `option 色值越出主题令牌派生空间（疑似硬编码）: ${c}`).toBe(true);
    }
  });

  it('should_refresh_colors_when_accent_changes', () => {
    const a = colorsOf(build('trend', TREND_DATA, LIGHT));
    const b = colorsOf(build('trend', TREND_DATA, LIGHT_ACCENT_B));
    const spaceB = derivationSpace(LIGHT_ACCENT_B);
    for (const c of b) {
      expect(spaceB.has(c), `切换强调色后仍有越出色值的颜色: ${c}`).toBe(true);
    }
    const changed = [...a].some((c) => !b.has(c)) || [...b].some((c) => !a.has(c));
    expect(changed).toBe(true); // 强调色切换 → 配色随之刷新（AC-2 主题联动）
  });

  it('should_refresh_colors_when_theme_mode_dark', () => {
    const light = colorsOf(build('trend', TREND_DATA, LIGHT));
    const dark = colorsOf(build('distribution', PIE_DATA, DARK));
    const spaceDark = derivationSpace(DARK);
    for (const c of dark) {
      expect(spaceDark.has(c), `暗色快照下仍有越出色值的颜色: ${c}`).toBe(true);
    }
    const changed = [...light].some((c) => !dark.has(c)) || [...dark].some((c) => !light.has(c));
    expect(changed).toBe(true); // 暗/亮切换 → 配色随令牌刷新
  });

  it('should_refresh_text_size_when_density_changes', () => {
    const comfortable = fontSizesOf(build('trend', TREND_DATA, LIGHT));
    const dense = fontSizesOf(build('trend', TREND_DATA, DENSE));
    expect(comfortable.length).toBeGreaterThan(0);
    expect(dense.length).toBeGreaterThan(0);
    const setEq = (xs: number[]) => new Set(xs);
    expect(setEq(dense)).not.toEqual(setEq(comfortable));
    expect(Math.max(...dense)).toBeGreaterThan(Math.max(...comfortable)); // 密度↑ → 字号单调↑（A-4）
  });
});
