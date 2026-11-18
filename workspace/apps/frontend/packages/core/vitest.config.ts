import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // 缺省 node（纯逻辑单测）；组件级用例（.tsx）经文件内 // @vitest-environment jsdom 声明环境
    environment: 'node',
    include: ['tests/**/*.test.ts', 'tests/**/*.test.tsx'],
  },
});