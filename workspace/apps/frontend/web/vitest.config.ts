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
    // 组件测试（*.test.tsx）使用持久化 jsdom 环境（同 worker 复用 window/document，消除 70+ 文件重复加载 antd）
    environmentMatchGlobs: [['**/*.test.tsx', './tests/shared-jsdom-env.ts']],
    include: ['tests/**/*.test.ts', 'tests/**/*.test.tsx'],
    // 全量并行 jsdom worker 在负载/CI 下把交互式 CRUD 用例拖至 5s 超时（S14 基线对照实验
    // 证实为既有环境性偶发，与改动无关）——全局放宽至 20s（单测实际耗时 0.1-8s）。
    testTimeout: 20000,
    minWorkers: 1,
    maxWorkers: 4,
    setupFiles: ['./tests/setup.ts'],
  },
});