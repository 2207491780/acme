import { defineWorkspace } from 'vitest/config';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { getPrebundlePaths } from './scripts/prebundle-test-deps.mjs';

const rootDir = fileURLToPath(new URL('.', import.meta.url));
const testsDir = join(rootDir, 'tests');

function collectTestFiles(dir: string, ext: '.test.tsx' | '.test.ts'): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...collectTestFiles(full, ext));
    } else if (entry.isFile() && entry.name.endsWith(ext)) {
      out.push(relative(rootDir, full).replace(/\\/g, '/'));
    }
  }
  return out;
}

const prebundles = getPrebundlePaths();
const hasPrebundles =
  existsSync(prebundles.antd) &&
  existsSync(prebundles.antdIcons) &&
  existsSync(prebundles.lucideReact) &&
  existsSync(prebundles.antvX6);

/**
 * 动态路由规则：
 * 1. .test.tsx：含模块级 vi.mock()、vi.useFakeTimers()、MSW setupServer() 或强全局壳耦合的文件进入隔离池
 *    （预打包 antd.cjs 将单文件 collect 从 ~7s 降至 <1.5s，隔离池亦高速完成）；
 *    其余 60 个 .test.tsx 按 LPT 权重均衡切入 5 个持久化 shared-jsdom singleFork 分片。
 * 2. .test.ts（node）：含 mocks/ 共享内存数据变异的文件走隔离池，其余纯逻辑单测进 singleFork 共享池。
 */
const ISOLATED_TSX_EXTRA = new Set([
  'tests/REQ20261121022910-packagesPage.test.tsx',
  'tests/REQ20261114120104-roleDataScope.test.tsx',
  'tests/REQ20261114120101-nav.test.tsx',
  'tests/REQ20261114120103-requestLifecycle.test.tsx',
]);

const KNOWN_WEIGHT_MS: Record<string, number> = {
  'tests/REQ20261121200415-preferencesBackendSync.test.tsx': 7530,
  'tests/REQ20261120104022-tabbar-scroll-preview.test.tsx': 6470,
  'tests/REQ20261122023213-schedulerAdminPage.test.tsx': 4975,
  'tests/REQ20261114120104-deptsPage.test.tsx': 4805,
  'tests/REQ20261119063129-tabs.test.tsx': 3390,
  'tests/REQ20261114120101-crudPages.test.tsx': 3015,
  'tests/REQ20261117025200-tenantEnhance.test.tsx': 2350,
  'tests/REQ20261125230807-tenants.test.tsx': 2230,
  'tests/REQ20261117061017-loggingJobs.test.tsx': 2060,
  'tests/REQ20261121043502-appearanceDrawer.test.tsx': 1860,
  'tests/REQ20261121010725-preferencesDrawer.test.tsx': 1790,
  'tests/REQ20261122233426-tenantInitTemplate.test.tsx': 1765,
  'tests/REQ20261120214653-permissionsPage.test.tsx': 1670,
  'tests/REQ20261125230807-rolesAuth.test.tsx': 1400,
};

const allTsxTests = collectTestFiles(testsDir, '.test.tsx');
const isolatedTsxTests = allTsxTests.filter(
  (relPath) =>
    ISOLATED_TSX_EXTRA.has(relPath) ||
    /\b(?:vi\.mock|vi\.useFakeTimers|setupServer)\s*\(/.test(readFileSync(join(rootDir, relPath), 'utf8')),
);
const sharedTsxTests = allTsxTests.filter((relPath) => !isolatedTsxTests.includes(relPath));

const allNodeTests = collectTestFiles(testsDir, '.test.ts');
const isolatedNodeTests = allNodeTests.filter((relPath) =>
  /\b(?:vi\.mock|setupServer|src\/mocks\/)/.test(readFileSync(join(rootDir, relPath), 'utf8')),
);
const sharedNodeTests = allNodeTests.filter((relPath) => !isolatedNodeTests.includes(relPath));

// LPT 贪心均衡装箱（缺省 5 个共享 DOM 分片，匹配主机物理核数避免 Vite 进程过载抖动）
const SHARD_COUNT = Math.max(1, Number.parseInt(process.env.ACME_WEB_SHARDS ?? '5', 10) || 5);
const shardLoads = Array.from({ length: SHARD_COUNT }, () => 0);
const sharedShards: string[][] = Array.from({ length: SHARD_COUNT }, () => []);
const weightedShared = sharedTsxTests
  .map((file) => ({
    file,
    weight: KNOWN_WEIGHT_MS[file] ?? Math.max(200, Math.round(statSync(join(rootDir, file)).size / 12)),
  }))
  .sort((a, b) => b.weight - a.weight);
for (const item of weightedShared) {
  let minIdx = 0;
  for (let i = 1; i < SHARD_COUNT; i++) {
    if (shardLoads[i]! < shardLoads[minIdx]!) minIdx = i;
  }
  sharedShards[minIdx]!.push(item.file);
  shardLoads[minIdx]! += item.weight;
}

const sharedAlias = [
  { find: '@acme/core', replacement: fileURLToPath(new URL('../packages/core/src/index.ts', import.meta.url)) },
  // R5 归因：vi.mock 不拦截组件内动态 await import('echarts')，alias 对动态 import 同样生效
  { find: /^echarts$/, replacement: fileURLToPath(new URL('./tests/echarts-stub.ts', import.meta.url)) },
  ...(hasPrebundles
    ? [
        // 仅精确匹配包根，不影响 antd/locale/zh_CN 等子路径导入
        { find: /^antd$/, replacement: prebundles.antd },
        { find: /^@ant-design\/icons$/, replacement: prebundles.antdIcons },
        { find: /^lucide-react$/, replacement: prebundles.lucideReact },
        { find: /^@antv\/x6$/, replacement: prebundles.antvX6 },
      ]
    : []),
];

const sharedResolve = { alias: sharedAlias };
// 让 Vitest 以原生 Node CJS 直接加载预打包的 4.8MB antd.cjs 与 antv-x6.cjs，
// 完全跳过 Vite SSR 模块转换管线（消除每进程 6~10s transform 开销）
const sharedServer = {
  deps: {
    external: [/acme-test-deps[\\/]/],
  },
};

export default defineWorkspace([
  {
    resolve: sharedResolve,
    test: {
      name: 'web-node-shared',
      environment: 'node',
      include: sharedNodeTests,
      isolate: false,
      server: sharedServer,
      pool: 'forks',
      poolOptions: {
        forks: {
          singleFork: true,
          isolate: false,
        },
      },
      testTimeout: 20000,
      setupFiles: ['./tests/setup.ts'],
    },
  },
  {
    resolve: sharedResolve,
    test: {
      name: 'web-node-isolated',
      environment: 'node',
      include: isolatedNodeTests,
      isolate: true,
      server: sharedServer,
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
        server: sharedServer,
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
      server: sharedServer,
      pool: 'forks',
      poolOptions: {
        forks: {
          isolate: true,
          minForks: 2,
          maxForks: 3,
        },
      },
      testTimeout: 20000,
      setupFiles: ['./tests/setup.ts'],
    },
  },
]);
