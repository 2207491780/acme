import { useMemo } from 'react';
import {
  createChartOption,
  metrics,
  useTimezoneStore,
  zonedDayjs,
  type AppState,
  type DistributionChartData,
  type TrendChartData,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { useSystemPrefersDark } from './useSystemPrefersDark';
import { themeSnapshot } from '../theme';

/**
 * Dashboard 图表统一数据 hook（REQ-20261114-120112，AC-1）：
 * - 无入参 → 用 Dashboard 既有统计（当前静态卡片数据）衍生：
 *   趋势 = 近 7 日发布单更新量；分布 = 发布单按平台分布（metrics.byPlatform）；
 * - 显式传入 null → 空态（trend/distribution 为 null 且 isEmpty=true）；
 * - 也可显式传入 { trend?, distribution? } 数据源（后续接统计端点时按 props 接入）。
 * 主题快照从既有 settings 派生（theme.ts 的 themeSnapshot），主题切换即整体刷新（AC-2）。
 */

export interface DashboardChartStats {
  trend?: TrendChartData;
  distribution?: DistributionChartData;
}

const TREND_DAYS = 7;

function deriveStats(state: AppState): DashboardChartStats {
  const labels: string[] = [];
  const values: number[] = [];
  // 按生效时区分桶（日期国际化·时区维度）：跨时区时「近 7 日」的日界随所选时区移动
  const today = zonedDayjs();
  for (let i = TREND_DAYS - 1; i >= 0; i -= 1) {
    const day = today.subtract(i, 'day');
    const key = day.format('YYYY-MM-DD');
    labels.push(day.format('M/D'));
    values.push(
      state.releases.filter((r) => zonedDayjs(r.updatedAt).format('YYYY-MM-DD') === key).length,
    );
  }
  const m = metrics(state);
  const distribution = {
    items: Object.entries(m.byPlatform).map(([name, value]) => ({ name, value: Number(value) })),
  };
  return { trend: { labels, values }, distribution };
}

export function useDashboardCharts(stats?: DashboardChartStats | null): {
  trend: Record<string, unknown> | null;
  distribution: Record<string, unknown> | null;
  isEmpty: boolean;
} {
  const state = useStore((s) => s.state);
  const settings = state.settings;
  const timezone = useTimezoneStore((s) => s.timezone);
  // mode=system 时图表快照同样按系统深浅色解析（themeSnapshot 内 resolveThemeMode）
  const systemPrefersDark = useSystemPrefersDark();
  const theme = useMemo(() => themeSnapshot(settings, systemPrefersDark), [settings, systemPrefersDark]);
  const derived = useMemo(() => deriveStats(state), [state, timezone]);
  const source = stats === undefined ? derived : stats;

  return useMemo(() => {
    if (stats === null || source === null) {
      return { trend: null, distribution: null, isEmpty: true };
    }
    const trendData = source.trend;
    const distData = source.distribution;
    const trend =
      trendData && trendData.values.length > 0
        ? createChartOption('trend', trendData, theme)
        : null;
    const distribution =
      distData && distData.items.length > 0
        ? createChartOption('distribution', distData, theme)
        : null;
    return { trend, distribution, isEmpty: trend === null && distribution === null };
  }, [stats, source, theme]);
}
