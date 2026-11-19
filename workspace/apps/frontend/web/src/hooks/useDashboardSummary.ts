import { useCallback, useEffect, useRef, useState } from 'react';
import { systemApi, type DashboardSummaryView } from '@acme/core';

/**
 * 平台概览聚合 hook（REQ-20261119-011524 契约⑤⑥）：挂载即取一次 + 手动刷新，
 * 成功取数后 15 分钟标记 stale（SWR 口径的「过期标记」，不自动重取——配合页面刷新按钮）。
 * - 失败不清空已取数据（保留上次成功值，可再次手动刷新）；
 * - data 为 null（未取到/端点异常）时由调用方按降级展示（统计卡显示 '—'）。
 * 零新依赖：useState + useEffect + useRef（无 SWR/TanStack Query）。
 */

/** 契约⑥：15 分钟 stale 标记阈值。 */
export const SUMMARY_STALE_AFTER_MS = 15 * 60 * 1000;
/** stale 巡检间隔（1 分钟粒度足够承载「15 分钟」标记语义）。 */
const STALE_TICK_MS = 60 * 1000;

export interface DashboardSummaryState {
  /** 聚合视图；null=尚未取到（加载中/失败/端点无数据），调用方按降级渲染。 */
  data: DashboardSummaryView | null;
  loading: boolean;
  /** 最近一次取数失败的错误信息（成功后清空；当前页面降级展示 '—'，不阻断首页）。 */
  error: string | null;
  /** 距上次成功取数超过 15 分钟（手动刷新成功后复位）。 */
  stale: boolean;
  /** 手动刷新（契约⑥）；失败时保留既有数据并置 stale。 */
  refresh: () => Promise<void>;
}

const errorTextOf = (ex: unknown): string =>
  ex instanceof Error ? ex.message : '平台概览加载失败';

export function useDashboardSummary(): DashboardSummaryState {
  const [data, setData] = useState<DashboardSummaryView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(true);
  const fetchedAtRef = useRef(0);

  const fetchSummary = useCallback(async (alive: () => boolean) => {
    setLoading(true);
    try {
      const summary = await systemApi.dashboardSummary();
      if (!alive()) {
        return;
      }
      fetchedAtRef.current = Date.now();
      setStale(false);
      setData(summary);
      setError(null);
    } catch (ex: unknown) {
      if (!alive()) {
        return;
      }
      setStale(true);
      setError(errorTextOf(ex));
    } finally {
      if (alive()) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void fetchSummary(() => alive);
    const timer = window.setInterval(() => {
      if (fetchedAtRef.current > 0 && Date.now() - fetchedAtRef.current >= SUMMARY_STALE_AFTER_MS) {
        setStale(true);
      }
    }, STALE_TICK_MS);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [fetchSummary]);

  const refresh = useCallback(() => fetchSummary(() => true), [fetchSummary]);

  return { data, loading, error, stale, refresh };
}
