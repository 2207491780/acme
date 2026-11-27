import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

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

// 定向传参（如单文件复跑或 --no-file-parallelism）直接透传给 vitest，零额外开销
if (extraArgs.length > 0) {
  const child = spawn(process.execPath, [vitestBin, 'run', ...extraArgs], {
    cwd: webRoot,
    stdio: 'inherit',
    env: { ...process.env },
  });
  child.on('close', (code) => process.exit(code ?? 1));
} else {
  // 全量运行：web-node 与 4 个 DOM 分片进程并发执行，避免 node/jsdom 混入同一 singleFork；
  // 每 DOM 进程仅加载 1 次 jsdom + antd，绕开 Vitest 2.x 全局 singleFork 串行限制。
  const groups = [
    { label: 'node + isolated', args: ['--project=web-node', '--project=web-dom-isolated'] },
    { label: 'dom-1', args: ['--project=web-dom-1'] },
    { label: 'dom-2', args: ['--project=web-dom-2'] },
    { label: 'dom-3', args: ['--project=web-dom-3'] },
    { label: 'dom-4', args: ['--project=web-dom-4'] },
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
        .filter((l) => /FAIL |× |AssertionError|TestingLibraryElementError|Found |Unable to find|Error:|❯ tests\/|Test Files|Tests\s+\d/.test(l));
      console.log(errLines.slice(-60).join('\n') || r.out.slice(-4000));
      if (r.err) console.error(r.err.slice(-2000));
    } else {
      for (const l of summaryLines.slice(-6)) {
        console.log(l);
      }
    }
  }
  const elapsedSec = ((Date.now() - t0) / 1000).toFixed(1);
  console.log(`\n@acme/web 4-shard parallel total wall time: ${elapsedSec}s`);
  process.exit(failed ? 1 : 0);
}
