import { useMemo } from 'react';
import {
  createChartOption,
  type DistributionChartData,
  type TrendChartData,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { useSystemPrefersDark } from './useSystemPrefersDark';
import { themeSnapshot } from '../theme';

/**
 * Dashboard 图表统一数据 hook（REQ-20261114-120112 AC-1；REQ-20261119-011524 契约⑤；
 * REQ-20261126-125836）：数据源仅来自显式入参（后端 summary）；无入参/null → 空态。
 * 发布单演示域删除后不再有本地派生统计。主题快照从既有 settings 派生（theme.ts 的
 * themeSnapshot），主题切换即整体刷新（AC-2）。
 */

export interface DashboardChartStats {
  trend?: TrendChartData;
  distribution?: DistributionChartData;
}

export function useDashboardCharts(stats?: DashboardChartStats | null): {
  trend: Record<string, unknown> | null;
  distribution: Record<string, unknown> | null;
  isEmpty: boolean;
} {
  const settings = useStore((s) => s.state.settings);
  // mode=system 时图表快照同样按系统深浅色解析（themeSnapshot 内 resolveThemeMode）
  const systemPrefersDark = useSystemPrefersDark();
  const theme = useMemo(() => themeSnapshot(settings, systemPrefersDark), [settings, systemPrefersDark]);

  return useMemo(() => {
    if (stats === null || stats === undefined) {
      return { trend: null, distribution: null, isEmpty: true };
    }
    const trendData = stats.trend;
    const distData = stats.distribution;
    const trend =
      trendData && trendData.values.length > 0
        ? createChartOption('trend', trendData, theme)
        : null;
    const distribution =
      distData && distData.items.length > 0
        ? createChartOption('distribution', distData, theme)
        : null;
    return { trend, distribution, isEmpty: trend === null && distribution === null };
  }, [stats, theme]);
}
