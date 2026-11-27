#!/usr/bin/env node
// 派生自 .ai/skills/code-review/scripts/l1-checks.mjs（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs
/**
 * l1-checks.mjs — 已有零依赖 L1 检查入口（不新增依赖、不造 lint）。
 *
 * 用法：
 *   node l1-checks.mjs desktop-check                 # pnpm --filter @acme/desktop check
 *   node l1-checks.mjs theme-tests                   # core / web / mobile 三处 theme 测试
 *   node l1-checks.mjs test-shard <包名>              # pnpm --filter <包名> test
 *   node l1-checks.mjs build                         # pnpm -r build
 *
 * 从 PATH 扫描 node_modules/pnpm/bin/pnpm.cjs 入口（无硬编码绝对路径），
 * 一律 execFile(process.execPath, [pnpmCli, ...], { shell: false })；
 * 失败或缺失输出 SKIP(原因)，退出码非零。只读运行既有检查，不写任何文件。
 */
import { execFile } from 'node:child_process';
import { stat } from 'node:fs/promises';
import { delimiter, resolve } from 'node:path';

function fail(message) {
  console.error(message);
  process.exit(1);
}

async function isFile(path) {
  try {
    return (await stat(path)).isFile();
  } catch {
    return false;
  }
}

/** PATH 项可能是 node_modules 本身或其 .bin，逐个尝试相对候选，不猜固定安装根。 */
async function findPnpmCli() {
  for (const entry of (process.env.PATH ?? '').split(delimiter)) {
    if (!entry) continue;
    const dir = resolve(entry);
    const candidates = [
      resolve(dir, 'pnpm', 'bin', 'pnpm.cjs'),
      resolve(dir, '..', 'pnpm', 'bin', 'pnpm.cjs'),
      resolve(dir, 'node_modules', 'pnpm', 'bin', 'pnpm.cjs'),
    ];
    for (const candidate of candidates) {
      if (await isFile(candidate)) return candidate;
    }
  }
  return null;
}

function runPnpm(pnpmCli, args) {
  return new Promise((done) => {
    const child = execFile(process.execPath, [pnpmCli, ...args], { shell: false });
    child.stdout?.pipe(process.stdout);
    child.stderr?.pipe(process.stderr);
    child.on('error', (error) => {
      console.error(`命令无法执行：${error.message}`);
      done(1);
    });
    child.on('close', (code, signal) => {
      if (signal) {
        console.log(`SKIP(信号 ${signal})`);
        done(1);
      } else {
        done(code ?? 1);
      }
    });
  });
}

const [subcommand, ...args] = process.argv.slice(2);
if (!['desktop-check', 'theme-tests', 'test-shard', 'build'].includes(subcommand)) {
  fail('用法: node l1-checks.mjs <desktop-check|theme-tests|test-shard <包名>|build>');
}
if (subcommand === 'test-shard' && args.length !== 1) {
  fail('用法: node l1-checks.mjs test-shard <包名>');
}

const pnpmCli = await findPnpmCli();
if (!pnpmCli) fail('SKIP(未在 PATH 找到 node_modules/pnpm/bin/pnpm.cjs，请确认 pnpm 已安装)');

let failed = false;
if (subcommand === 'desktop-check') {
  const code = await runPnpm(pnpmCli, ['--filter', '@acme/desktop', 'check']);
  failed = code !== 0;
  console.log(`desktop-check: ${failed ? 'FAIL' : 'PASS'}`);
} else if (subcommand === 'theme-tests') {
  const targets = [
    ['@acme/core', 'tests/theme.test.ts'],
    ['@acme/web', 'tests/theme.test.ts'],
    ['@acme/mobile', 'tests/theme.test.ts'],
  ];
  for (const [pkg, file] of targets) {
    const code = await runPnpm(pnpmCli, ['--filter', pkg, 'exec', 'vitest', 'run', file]);
    if (code !== 0) failed = true;
    console.log(`theme-tests ${pkg} ${file}: ${code === 0 ? 'PASS' : 'FAIL'}`);
  }
} else if (subcommand === 'test-shard') {
  const code = await runPnpm(pnpmCli, ['--filter', args[0], 'test']);
  failed = code !== 0;
  console.log(`test-shard ${args[0]}: ${failed ? 'FAIL' : 'PASS'}`);
} else {
  const code = await runPnpm(pnpmCli, ['-r', 'build']);
  failed = code !== 0;
  console.log(`build: ${failed ? 'FAIL' : 'PASS'}`);
}

process.exit(failed ? 1 : 0);
