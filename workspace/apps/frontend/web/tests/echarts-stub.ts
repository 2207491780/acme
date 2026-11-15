import { vi } from 'vitest';

// 测试桩：vitest alias 将 'echarts' 指到本文件（动态 import 亦被 alias 拦截）。
export const init = vi.fn(() => ({
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
}));
export const use = vi.fn();
export const dispose = vi.fn();
export default { init, use, dispose };
