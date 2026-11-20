import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  resolve: {
    alias: {
      '@acme/core': fileURLToPath(new URL('../packages/core/src/index.ts', import.meta.url)),
      // R5 归因：vi.mock 不拦截组件内动态 await import('echarts')，alias 对动态 import 同样
      // 生效 → 全局指向测试桩（仅测试配置，不影响构建产物）
      echarts: fileURLToPath(new URL('./tests/echarts-stub.ts', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    // 组件测试（*.test.tsx）需要真实 DOM → jsdom；store/工具测试维持 node + 最小桩
    environmentMatchGlobs: [['**/*.test.tsx', 'jsdom']],
    include: ['tests/**/*.test.ts', 'tests/**/*.test.tsx'],
    // 全量并行 jsdom worker 在负载/CI 下把交互式 CRUD 用例拖至 5s 超时（S14 基线对照实验
    // 证实为既有环境性偶发，与改动无关）——全局放宽至 20s（单测实际耗时 0.1-8s）。
    testTimeout: 20000,
    // worker 上限（2026-11-20）：本机多会话/多代理并发跑套件时出现过 9x 过载（wall 718s vs
    // 冷静基线 197s），63~73 条交互式用例撞超时形成轮换误报——上限 4 封顶最坏扇出，
    // 正常环境吞吐无损（单套件实测并行收益在 4 worker 后已趋平）。minWorkers 须显式
    // 降配：vitest 默认 minWorkers=核数/2，多核机上会与 maxWorkers=4 冲突（RangeError）。
    minWorkers: 1,
    maxWorkers: 4,
    setupFiles: ['./tests/setup.ts'],
  },
});