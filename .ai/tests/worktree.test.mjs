import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const tempRoot = join(root, '.ai-tmp');
const SCRIPT = join(root, '.ai/worktree.mjs');

async function makeRepo(t) {
  const sandbox = await mkdtemp(join(tempRoot, 'worktree-test-'));
  t.after(() => rm(sandbox, { recursive: true, force: true }));
  const repo = join(sandbox, 'repo');
  await mkdir(repo);
  const g = (args) => spawnSync('git', args, { cwd: repo, encoding: 'utf8' });
  g(['init', '-q']);
  g(['config', 'user.email', 'worktree-test@example.com']);
  g(['config', 'user.name', 'worktree-test']);
  g(['checkout', '-q', '-b', 'main']);
  g(['commit', '--allow-empty', '-m', 'chore: base']);
  return { repo, g };
}

const run = (repo, args) => spawnSync('node', [SCRIPT, ...args], { cwd: repo, encoding: 'utf8' });

test('should_create_worktree_and_branch_when_add', async (t) => {
  const { repo, g } = await makeRepo(t);
  const r = run(repo, ['add', 'feat/1-demo']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(existsSync(join(repo, '.worktrees/feat-1-demo')), '工作树目录应存在');
  assert.equal(g(['rev-parse', '--verify', 'refs/heads/feat/1-demo']).status, 0, '分支应存在');
  assert.match(r.stdout, /工作树已创建/);
});

test('should_reject_when_branch_already_exists', async (t) => {
  const { repo } = await makeRepo(t);
  run(repo, ['add', 'feat/1-demo']);
  const r = run(repo, ['add', 'feat/1-demo']);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /已存在/);
});

test('should_warn_when_branch_name_not_in_model', async (t) => {
  const { repo } = await makeRepo(t);
  const r = run(repo, ['add', 'playground-x']);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stderr, /不符合分支模型/);
});

test('should_reject_when_branch_name_illegal', async (t) => {
  const { repo } = await makeRepo(t);
  const r = run(repo, ['add', 'a b/c']);
  assert.equal(r.status, 2);
});

test('should_remove_worktree_when_remove', async (t) => {
  const { repo } = await makeRepo(t);
  run(repo, ['add', 'feat/2-tmp']);
  const r = run(repo, ['remove', 'feat/2-tmp']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(!existsSync(join(repo, '.worktrees/feat-2-tmp')), '工作树目录应已移除');
  // 分支保留，删除由授权分级约束
  const g = (args) => spawnSync('git', args, { cwd: repo, encoding: 'utf8' });
  assert.equal(g(['rev-parse', '--verify', 'refs/heads/feat/2-tmp']).status, 0, '分支不应被自动删除');
});

test('should_fail_when_remove_unknown_worktree', async (t) => {
  const { repo } = await makeRepo(t);
  const r = run(repo, ['remove', 'feat/404']);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /未找到工作树/);
});

test('should_list_worktrees', async (t) => {
  const { repo } = await makeRepo(t);
  run(repo, ['add', 'feat/3-ls']);
  const r = run(repo, ['list']);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /feat-3-ls/);
});

test('should_exit_2_when_usage_unknown_command', async (t) => {
  const { repo } = await makeRepo(t);
  const r = run(repo, ['nonsense']);
  assert.equal(r.status, 2);
});
