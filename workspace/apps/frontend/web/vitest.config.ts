import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  resolve: {
    alias: {
      '@acme/core': fileURLToPath(new URL('../packages/core/src/index.ts', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    // 组件测试（*.test.tsx）需要真实 DOM → jsdom；store/工具测试维持 node + 最小桩
    environmentMatchGlobs: [['**/*.test.tsx', 'jsdom']],
    include: ['tests/**/*.test.ts', 'tests/**/*.test.tsx'],
    setupFiles: ['./tests/setup.ts'],
  },
});