import { defineWorkspace } from 'vitest/config';
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const rootDir = fileURLToPath(new URL('.', import.meta.url));
const testsDir = join(rootDir, 'tests');

function collectTestFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...collectTestFiles(full));
    } else if (entry.isFile() && entry.name.endsWith('.test.tsx')) {
      out.push(relative(rootDir, full).replace(/\\/g, '/'));
    }
  }
  return out;
}

/**
 * 动态识别含模块级 vi.mock()、vi.useFakeTimers() 或 MSW setupServer() 的 .test.tsx 文件，
 * 自动路由至隔离池（isolate: true），防止跨文件模块 mock / 假时钟泄漏；
 * 其余绝大多数 .test.tsx 进入持久化 shared-jsdom 池（isolate: false + singleFork），
 * 同 worker 仅解析 1 次 antd / @ant-design/icons / react-dom，零维护成本。
 */
const ISOLATED_EXTRA = new Set([
  'tests/REQ20261121022910-packagesPage.test.tsx',
  'tests/REQ20261114120104-roleDataScope.test.tsx',
  'tests/REQ20261114120101-nav.test.tsx',
]);
const allTsxTests = collectTestFiles(testsDir);
const isolatedTsxTests = allTsxTests.filter(
  (relPath) =>
    ISOLATED_EXTRA.has(relPath) ||
    /\b(?:vi\.mock|vi\.useFakeTimers|setupServer)\s*\(/.test(readFileSync(join(rootDir, relPath), 'utf8')),
);
const sharedTsxTests = allTsxTests.filter((relPath) => !isolatedTsxTests.includes(relPath));

// 按轮询（round-robin）把无泄漏 .test.tsx 均匀切为 4 个 singleFork 分片，避免重型 REQ 用例扎堆尾部
const sharedShards: string[][] = [[], [], [], []];
sharedTsxTests.forEach((file, idx) => {
  sharedShards[idx % 4].push(file);
});

const sharedResolve = {
  alias: {
    '@acme/core': fileURLToPath(new URL('../packages/core/src/index.ts', import.meta.url)),
    // R5 归因：vi.mock 不拦截组件内动态 await import('echarts')，alias 对动态 import 同样生效
    echarts: fileURLToPath(new URL('./tests/echarts-stub.ts', import.meta.url)),
  },
};

export default defineWorkspace([
  {
    resolve: sharedResolve,
    test: {
      name: 'web-node',
      environment: 'node',
      include: ['tests/**/*.test.ts'],
      isolate: true,
      pool: 'forks',
      poolOptions: {
        forks: {
          isolate: true,
          minForks: 1,
          maxForks: 2,
        },
      },
      testTimeout: 20000,
      setupFiles: ['./tests/setup.ts'],
    },
  },
  ...sharedShards
    .filter((files) => files.length > 0)
    .map((files, idx) => ({
      resolve: sharedResolve,
      test: {
        name: `web-dom-${idx + 1}`,
        environment: './tests/shared-jsdom-env.ts',
        include: files,
        isolate: false,
        pool: 'forks' as const,
        poolOptions: {
          forks: {
            singleFork: true,
            isolate: false,
          },
        },
        testTimeout: 20000,
        setupFiles: ['./tests/setup.ts'],
      },
    })),
  {
    resolve: sharedResolve,
    test: {
      name: 'web-dom-isolated',
      environment: 'jsdom',
      include: isolatedTsxTests,
      isolate: true,
      pool: 'forks',
      poolOptions: {
        forks: {
          isolate: true,
          minForks: 1,
          maxForks: 2,
        },
      },
      testTimeout: 20000,
      setupFiles: ['./tests/setup.ts'],
    },
  },
]);
