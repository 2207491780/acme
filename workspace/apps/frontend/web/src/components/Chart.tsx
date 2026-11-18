import { useEffect, useRef } from 'react';
import { Empty, Spin } from 'antd';

/**
 * echarts 渲染组件（REQ-20261114-120112，AC-1）：
 * - echarts 懒加载（动态 import('echarts')，echarts 仅入 web 包依赖）；
 * - props { option?, loading? }：option 缺省 → antd Empty 空态且不初始化 echarts；
 *   loading=true → antd Spin 加载态；有 option → init + setOption。
 */

/** 最小实例面（不耦合 echarts 类型，渲染容器负责生命周期）。 */
interface EChartsLike {
  setOption: (option: unknown) => void;
  resize: () => void;
  dispose: () => void;
}

interface EchartsModuleLike {
  init: (el: HTMLElement) => EChartsLike;
}

const CHART_HEIGHT = 300;

export function Chart({ option, loading = false }: { option?: unknown; loading?: boolean }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<EChartsLike | null>(null);

  useEffect(() => {
    if (!option) {
      return undefined;
    }
    let cancelled = false;
    void (async () => {
      try {
        // 懒加载：echarts 只在首块图表实际渲染时进运行时（动态 import 不被 vi.mock 之外的路径触碰）
        const echarts = (await import('echarts')) as unknown as EchartsModuleLike;
        if (cancelled || !containerRef.current) {
          return;
        }
        const instance = echarts.init(containerRef.current);
        instance.setOption(option);
        chartRef.current = instance;
      } catch {
        // echarts 加载/初始化失败：保持空容器，不阻塞页面
      }
    })();
    return () => {
      cancelled = true;
      chartRef.current?.dispose();
      chartRef.current = null;
    };
  }, [option, loading]);

  useEffect(() => {
    const onResize = () => chartRef.current?.resize();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  if (loading) {
    return <Spin style={{ display: 'block', margin: `${CHART_HEIGHT / 2 - 16}px auto` }} />;
  }
  if (!option) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        style={{ padding: `${CHART_HEIGHT / 3}px 0` }}
        description={false}
      />
    );
  }
  return (
    <div ref={containerRef} style={{ width: '100%', height: CHART_HEIGHT }} />
  );
}
