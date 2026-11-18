/**
 * REQ-20261114-120112 AC-1（web 侧）：Chart 组件契约用例。
 *
 * 【round2 delta 说明】本文件上一轮被事故覆盖为 placeholder，本轮按原规格重建。
 *
 * 文件头注释沿用原 A-9/A-10/A-11 假定口径（待 TL 裁决）：
 *   A-9  web/src/components/Chart 命名导出 Chart，props { option?: unknown; loading?: boolean }；
 *        option 缺省 → antd Empty 空态且不初始化 echarts；loading=true → antd Spin。
 *   A-10 echarts 经 'echarts' 包导入（懒加载动态 import 由 vitest alias 指向
 *        tests/echarts-stub.ts 全局拦截；R5 归因：vi.mock 不拦截动态 import）。
 *
 * 装配照既有范本 web/tests/REQ20261114120103-errorBoundary.test.tsx：
 *   <AntApp> 包裹 + beforeEach/afterEach 共享环境（storage/timers）teardown 清理。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { init } from 'echarts';
import { Chart } from '../src/components/Chart';

vi.setConfig({ testTimeout: 15000 });

// echarts 经 vitest alias 指向 tests/echarts-stub.ts（R5：vi.mock 不拦截组件内动态 import）
// 最小合法 option（A-9：仅验证「收到该 option」的透传语义，不假设图表类型细节）
const MIN_OPTION = {
  xAxis: { type: 'category', data: ['a'] },
  yAxis: { type: 'value' },
  series: [{ type: 'bar', data: [1] }],
};

// ————— 共享环境 teardown 清理（errorBoundary 范本口径） —————
beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  // R5：init 来自 alias 指到的桩模块（vi.fn），仅清调用记录，保留桩实现
  // R6：init.mockClear 会被 TS 按 echarts 真实类型解析报 TS2339，改用 vi.mocked 取 mock 面
  vi.mocked(init).mockClear();
});

afterEach(() => {
  cleanup();
  // 定时器卫生顺序（120102 wsClient 教训）：先还原 mock 再做其余清理
  vi.restoreAllMocks();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

describe('REQ20261114120112 Chart（AC-1 web 侧）', () => {
  it('should_init_echarts_and_set_option_when_option_provided', async () => {
    render(<AntApp><Chart option={MIN_OPTION} /></AntApp>);

    // A-9/A-10：option 提供时初始化 echarts，并把 option 原样透传给实例 setOption
    // （组件内为动态 import，初始化异步 → 同步断言必败，用 vi.waitFor 收敛）
    const initMock = vi.mocked(init);
    await vi.waitFor(() => expect(initMock).toHaveBeenCalled());
    const instance = initMock.mock.results[0]!.value;
    expect(instance.setOption).toHaveBeenCalled();
    expect(vi.mocked(instance.setOption).mock.calls[0]![0]).toMatchObject(MIN_OPTION);
  });

  it('should_show_loading_state_when_loading_true', async () => {
    // A-9：loading=true → antd Spin（与 option 缺省空态隔离，同时提供合法 option）
    const { container } = render(<AntApp><Chart option={MIN_OPTION} loading /></AntApp>);
    // 给动态 import/setOption 的异步链一个收敛 tick 后再断言
    await vi.waitFor(() => expect(container.querySelector('.ant-spin')).toBeTruthy());
  });

  it('should_show_empty_state_when_no_option', async () => {
    // A-9：option 缺省 → antd Empty 空态，且不初始化 echarts
    const { container } = render(<AntApp><Chart /></AntApp>);
    // 先等空态渲染收敛，再断言 init 未被调用（前置空态尚未出现时断言无意义）
    await vi.waitFor(() => expect(container.querySelector('.ant-empty')).toBeTruthy());
    expect(vi.mocked(init)).not.toHaveBeenCalled();
  });
});
