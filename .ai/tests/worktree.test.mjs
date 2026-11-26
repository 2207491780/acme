import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
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

// 6h GC 宽限期会让刚创建的工作树一律跳过回收；测试注入 0 使 gc 用例可断言实际判定逻辑。
const run = (repo, args) => spawnSync('node', [SCRIPT, ...args], {
  cwd: repo,
  encoding: 'utf8',
  env: { ...process.env, ACME_WORKTREE_GC_MIN_AGE_MS: '0' },
});

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

// ── gc（2026-11-20 增量）：已 push 的干净工作树自动清理 ──

async function makeRepoWithOrigin(t) {
  const sandbox = await mkdtemp(join(tempRoot, 'worktree-gc-'));
  t.after(() => rm(sandbox, { recursive: true, force: true }));
  const repo = join(sandbox, 'repo');
  const bare = join(sandbox, 'origin.git');
  await mkdir(bare);
  await mkdir(repo);
  spawnSync('git', ['init', '-q', '--bare', bare]);
  const g = (args) => spawnSync('git', args, { cwd: repo, encoding: 'utf8' });
  g(['init', '-q']);
  g(['config', 'user.email', 'worktree-test@example.com']);
  g(['config', 'user.name', 'worktree-test']);
  g(['checkout', '-q', '-b', 'acme-foundation']);
  g(['commit', '--allow-empty', '-m', 'chore: base']);
  g(['remote', 'add', 'origin', bare]);
  g(['push', '-q', 'origin', 'acme-foundation']);
  const gw = (dir, args) => spawnSync('git', args, { cwd: dir, encoding: 'utf8' });
  const commitInWorktree = (branch, message) => {
    const dir = join(repo, '.worktrees', branch.replaceAll('/', '-'));
    gw(dir, ['commit', '--allow-empty', '--allow-empty-message', '-m', message]);
  };
  const writeFileIn = async (branch, name, content) => {
    const dir = join(repo, '.worktrees', branch.replaceAll('/', '-'));
    await writeFile(join(dir, name), content);
  };
  const mergeAndPush = (branch) => {
    g(['merge', '--ff-only', branch]);
    g(['push', '-q', 'origin', 'acme-foundation']);
  };
  return { repo, g, commitInWorktree, mergeAndPush, writeFileIn };
}

test('should_gc_merged_pushed_clean_worktree_when_gc', async (t) => {
  const { repo, commitInWorktree, mergeAndPush } = await makeRepoWithOrigin(t);
  run(repo, ['add', 'feat/1-done']);
  commitInWorktree('feat/1-done', 'feat: done');
  mergeAndPush('feat/1-done');
  const r = run(repo, ['gc']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(!existsSync(join(repo, '.worktrees/feat-1-done')), '已 push 的干净工作树应被清理');
  const g = (args) => spawnSync('git', args, { cwd: repo, encoding: 'utf8' });
  assert.equal(g(['rev-parse', '--verify', 'refs/heads/feat/1-done']).status, 0, '分支应保留（授权分级）');
  assert.match(r.stdout, /已清理/);
});

test('should_keep_dirty_worktree_when_gc', async (t) => {
  const { repo, writeFileIn } = await makeRepoWithOrigin(t);
  run(repo, ['add', 'feat/2-dirty']);
  await writeFileIn('feat/2-dirty', 'scratch.txt', 'dirty');
  const r = run(repo, ['gc']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(existsSync(join(repo, '.worktrees/feat-2-dirty')), '有未提交修改的工作树必须保留');
  assert.match(r.stdout, /未提交修改/);
});

test('should_keep_merged_but_unpushed_worktree_when_gc', async (t) => {
  const { repo, g, commitInWorktree } = await makeRepoWithOrigin(t);
  run(repo, ['add', 'feat/3-ahead']);
  commitInWorktree('feat/3-ahead', 'feat: ahead');
  g(['merge', '--ff-only', 'feat/3-ahead']); // 只合并不 push
  const r = run(repo, ['gc']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(existsSync(join(repo, '.worktrees/feat-3-ahead')), '未 push 的工作树必须保留');
});

test('should_auto_gc_when_add_new_worktree', async (t) => {
  const { repo, commitInWorktree, mergeAndPush } = await makeRepoWithOrigin(t);
  run(repo, ['add', 'feat/4-old']);
  commitInWorktree('feat/4-old', 'feat: old');
  mergeAndPush('feat/4-old');
  const r = run(repo, ['add', 'feat/5-new']);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /自动清理/);
  assert.ok(!existsSync(join(repo, '.worktrees/feat-4-old')), 'add 时应自动清理已 push 的干净工作树');
  assert.ok(existsSync(join(repo, '.worktrees/feat-5-new')), '新工作树应正常创建');
});

test('should_keep_mirror_checkout_when_gc', async (t) => {
  const { repo } = await makeRepoWithOrigin(t);
  run(repo, ['add', 'ai-coding-support']); // tip=已 push 的 acme-foundation HEAD，满足 isPushed
  const r = run(repo, ['gc']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(existsSync(join(repo, '.worktrees/ai-coding-support')), '镜像常驻检出必须跳过');
  assert.match(r.stdout, /镜像对齐常驻检出/);
});

// ── gc --branches（2026-11-20 增量）：已随主线 push 的本地分支回收 ──

test('should_delete_merged_pushed_branch_when_gc_branches', async (t) => {
  const { repo, g, commitInWorktree, mergeAndPush } = await makeRepoWithOrigin(t);
  run(repo, ['add', 'feat/6-branch']);
  commitInWorktree('feat/6-branch', 'feat: b');
  mergeAndPush('feat/6-branch');
  run(repo, ['remove', 'feat/6-branch']);
  const r = run(repo, ['gc', '--branches']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(!String(g(['branch', '--list', 'feat/6-branch']).stdout).includes('feat/6-branch'), '已随主线 push 的合并分支应被删除');
  assert.match(r.stdout, /已删分支：feat\/6-branch/);
  assert.match(r.stdout, /保护分支，跳过/);
});

test('should_keep_unpushed_branch_when_gc_branches', async (t) => {
  const { repo, g } = await makeRepoWithOrigin(t);
  // 造数：分支 tip 必须含未 push 的独有提交（与已 push 主线同 tip 的分支内容已在远端，不算未 push）
  g(['checkout', '-q', '-b', 'feat/local-only']);
  g(['commit', '--allow-empty', '-m', 'feat: local only']);
  g(['checkout', '-q', 'acme-foundation']);
  const r = run(repo, ['gc', '--branches']);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(g(['rev-parse', '--verify', 'refs/heads/feat/local-only']).status, 0, '未 push 分支必须保留');
  assert.match(r.stdout, /未随主线 push/);
});

test('should_keep_protected_and_checkedout_branches_when_gc_branches', async (t) => {
  const { repo, g, commitInWorktree } = await makeRepoWithOrigin(t);
  // 造数：检出中分支带未 push 提交（否则其工作树会先被工作树 GC 回收，分支随之按已 push 回收）
  run(repo, ['add', 'feat/7-checkedout']);
  commitInWorktree('feat/7-checkedout', 'feat: unpushed');
  const r = run(repo, ['gc', '--branches']);
  assert.equal(r.status, 0, r.stderr);
  assert.ok(existsSync(join(repo, '.worktrees/feat-7-checkedout')), '未 push 的工作树必须保留');
  assert.equal(g(['rev-parse', '--verify', 'refs/heads/feat/7-checkedout']).status, 0, '检出中分支必须保留');
  assert.match(r.stdout, /检出于工作树，跳过/);
  assert.equal(g(['rev-parse', '--verify', 'refs/heads/acme-foundation']).status, 0, '保护分支必须保留');
  assert.match(r.stdout, /保护分支，跳过/);
});
