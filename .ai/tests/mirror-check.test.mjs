import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const tempRoot = join(root, '.ai-tmp');
const SCRIPT = join(root, '.ai/hooks/mirror-check.mjs');

// 沙箱落在 .ai-tmp/（不入库）；仅用 git 本地命令，不依赖远端。
async function makeRepo(t) {
  const sandbox = await mkdtemp(join(tempRoot, 'mirror-test-'));
  t.after(() => rm(sandbox, { recursive: true, force: true }));
  const repo = join(sandbox, 'repo');
  await mkdir(repo);
  const g = (args) => spawnSync('git', args, { cwd: repo, encoding: 'utf8' });
  g(['init', '-q']);
  g(['config', 'user.email', 'mirror-test@example.com']);
  g(['config', 'user.name', 'mirror-test']);
  g(['checkout', '-q', '-b', 'acme-foundation']);
  g(['commit', '--allow-empty', '-m', 'chore: base']);
  return { repo, g };
}

const run = (repo, args = []) => spawnSync('node', [SCRIPT, ...args], { cwd: repo, encoding: 'utf8' });

async function seedAiConfig(repo, g, { ai, app }) {
  await mkdir(join(repo, '.ai/rules'), { recursive: true });
  await writeFile(join(repo, '.ai/rules/sample.md'), ai, 'utf8');
  await writeFile(join(repo, 'app.txt'), app, 'utf8');
  g(['add', '.']);
  g(['commit', '-q', '-m', 'chore: seed']);
}

test('should_pass_when_ai_paths_match_and_app_differs', async (t) => {
  const { repo, g } = await makeRepo(t);
  await seedAiConfig(repo, g, { ai: 'A\n', app: 'v1\n' });
  g(['checkout', '-q', '-b', 'ai-coding-support']);
  await writeFile(join(repo, 'app.txt'), 'v2\n', 'utf8');
  g(['commit', '-q', '-am', 'chore: app only']);
  g(['checkout', '-q', 'acme-foundation']);

  const r = run(repo);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /镜像一致/);
});

test('should_fail_when_ai_paths_differ', async (t) => {
  const { repo, g } = await makeRepo(t);
  await seedAiConfig(repo, g, { ai: 'A\n', app: 'v1\n' });
  g(['checkout', '-q', '-b', 'ai-coding-support']);
  await writeFile(join(repo, '.ai/rules/sample.md'), 'B\n', 'utf8');
  g(['commit', '-q', '-am', 'chore: ai drift']);
  g(['checkout', '-q', 'acme-foundation']);

  const r = run(repo);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /镜像/);
});

test('should_fail_when_support_branch_missing', async (t) => {
  const { repo, g } = await makeRepo(t);
  await seedAiConfig(repo, g, { ai: 'A\n', app: 'v1\n' });

  const r = run(repo);
  assert.equal(r.status, 1);
  // 缺分支报错 UX（遗留项 2026-11-14-#4）：点名缺失分支 + 给出补救指引
  assert.match(r.stderr, /缺少策略分支 ai-coding-support/);
  assert.match(r.stderr, /补救/);
  assert.match(r.stderr, /git fetch origin/);
});

test('should_fall_back_to_branch_head_when_args_are_null_oid_of_any_length', async (t) => {
  // 空 SHA 入参不硬编码长度（遗留项 2026-11-14-#3）：64 位零值（SHA-256 仓库口径）同样回退本地分支 HEAD
  const { repo, g } = await makeRepo(t);
  await seedAiConfig(repo, g, { ai: 'A\n', app: 'v1\n' });
  g(['checkout', '-q', '-b', 'ai-coding-support']);
  g(['checkout', '-q', 'acme-foundation']);

  const r = run(repo, ['0'.repeat(64), '0'.repeat(64)]);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /镜像一致/);
});

test('should_compare_pushed_sha_when_provided', async (t) => {
  const { repo, g } = await makeRepo(t);
  await seedAiConfig(repo, g, { ai: 'A\n', app: 'v1\n' });
  const acme = g(['rev-parse', 'HEAD']).stdout.trim();
  g(['checkout', '-q', '-b', 'ai-coding-support']);
  await writeFile(join(repo, '.ai/rules/sample.md'), 'B\n', 'utf8');
  g(['commit', '-q', '-am', 'chore: ai drift']);
  g(['checkout', '-q', 'acme-foundation']);

  const r = run(repo, [acme, acme]);
  assert.equal(r.status, 0, r.stdout + r.stderr);
});
