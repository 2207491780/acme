import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { ensureTestPrebundles } from './prebundle-test-deps.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(__dirname, '..');
const extraArgs = process.argv.slice(2);

const vitestBin = resolve(webRoot, 'node_modules', 'vitest', 'vitest.mjs');

function runVitest(args, label) {
  return new Promise((resolvePromise) => {
    const child = spawn(process.execPath, [vitestBin, 'run', ...args], {
      cwd: webRoot,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env },
    });
    let out = '';
    let err = '';
    child.stdout.on('data', (chunk) => {
      out += chunk.toString();
    });
    child.stderr.on('data', (chunk) => {
      err += chunk.toString();
    });
    child.on('close', (code) => {
      resolvePromise({ label, code: code ?? 1, out, err });
    });
  });
}

// 首次或依赖版本变更时一次性预打包 antd / @ant-design/icons / lucide-react / @antv/x6（~1.5s，后续命中缓存 0ms）
await ensureTestPrebundles();

// 定向传参（如单文件复跑或 --no-file-parallelism）直接透传给 vitest，零额外开销
if (extraArgs.length > 0) {
  const child = spawn(process.execPath, [vitestBin, 'run', ...extraArgs], {
    cwd: webRoot,
    stdio: 'inherit',
    env: { ...process.env },
  });
  child.on('close', (code) => process.exit(code ?? 1));
} else {
  // 全量运行：node、隔离池与 N 个 LPT 均衡 DOM 分片并发执行，匹配物理 CPU 核数避免过载抖动；
  // 每共享 DOM 分片仅加载 1 次 jsdom + 预打包 antd（~300ms），绕开 Vitest 2.x 全局 singleFork 串行限制。
  const shardCount = Math.max(1, Number.parseInt(process.env.ACME_WEB_SHARDS ?? '5', 10) || 5);
  const groups = [
    { label: 'node', args: ['--project=web-node-shared', '--project=web-node-isolated'] },
    { label: 'isolated', args: ['--project=web-dom-isolated'] },
    ...Array.from({ length: shardCount }, (_, i) => ({
      label: `dom-${i + 1}`,
      args: [`--project=web-dom-${i + 1}`],
    })),
  ];

  const t0 = Date.now();
  const results = await Promise.all(groups.map((g) => runVitest(g.args, g.label)));

  let failed = false;
  for (const r of results) {
    const summaryLines = r.out
      .split(/\r?\n/)
      .filter((l) => /Test Files|Tests\s+\d|Duration|FAIL|AssertionError|Error:/.test(l));
    console.log(`\n=== ${r.label} (exit=${r.code}) ===`);
    if (r.code !== 0) {
      failed = true;
      const errLines = r.out
        .split(/\r?\n/)
        .filter((l) =>
          /FAIL |× |AssertionError|TestingLibraryElementError|Found |Unable to find|Error:|❯ tests\/|Test Files|Tests\s+\d/.test(
            l,
          ),
        );
      console.log(errLines.slice(-60).join('\n') || r.out.slice(-4000));
      if (r.err) console.error(r.err.slice(-2000));
    } else {
      for (const l of summaryLines.slice(-6)) {
        console.log(l);
      }
    }
  }
  const elapsedSec = ((Date.now() - t0) / 1000).toFixed(1);
  console.log(`\n@acme/web ${shardCount}-shard parallel total wall time: ${elapsedSec}s`);
  process.exit(failed ? 1 : 0);
}
