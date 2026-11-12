import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const tempRoot = join(root, '.ai-tmp');
const HOOKS = join(root, '.ai/hooks');
const ZERO = '0'.repeat(40);

// 沙箱固定落在仓库 .ai-tmp/（不入库）；每个用例独占目录，用例结束即清理。
async function makeRepo(t, { withHooks = false } = {}) {
  const sandbox = await mkdtemp(join(tempRoot, 'hooks-test-'));
  t.after(() => rm(sandbox, { recursive: true, force: true }));
  const repo = join(sandbox, 'repo');
  await mkdir(repo);
  if (withHooks) {
    await mkdir(join(repo, '.ai/hooks'), { recursive: true });
    for (const h of ['pre-commit', 'commit-msg', 'pre-push']) {
      await copyFile(join(HOOKS, h), join(repo, '.ai/hooks', h));
    }
  }
  const g = (args) => spawnSync('git', args, { cwd: repo, encoding: 'utf8' });
  g(['init', '-q']);
  g(['config', 'user.email', 'hook-test@example.com']);
  g(['config', 'user.name', 'hook-test']);
  g(['checkout', '-q', '-b', 'main']); // 沙箱 init 默认分支可能是 master，显式对齐受保护分支名
  g(['commit', '--allow-empty', '-m', 'chore: base']); // C1
  return { repo, g };
}

const commitMsg = (repo, msgFile) => spawnSync('sh', [join(HOOKS, 'commit-msg'), msgFile], { cwd: repo, encoding: 'utf8' });
const prePush = (repo, input) => spawnSync('sh', [join(HOOKS, 'pre-push')], { cwd: repo, encoding: 'utf8', input });

async function writeMsg(repo, subject) {
  const file = join(repo, `msg-${Math.random().toString(36).slice(2)}.txt`);
  await writeFile(file, `${subject}\n\n正文说明\n`, 'utf8');
  return file;
}

// 构造提交历史：main = C1 → C2；sibling 为与 C2 平行的远端独有提交（非本地祖先）。
async function prepareHistory(repo, g) {
  g(['checkout', '-q', '--detach']);
  g(['commit', '--allow-empty', '-m', 'chore: sibling']);
  const sibling = g(['rev-parse', 'HEAD']).stdout.trim();
  g(['checkout', '-q', 'main']);
  g(['commit', '--allow-empty', '-m', 'chore: forward']); // C2
  const forward = g(['rev-parse', 'HEAD']).stdout.trim();
  const base = g(['rev-parse', 'HEAD~']).stdout.trim(); // C1
  return { base, sibling, forward };
}

// ---- commit-msg ----

test('should_pass_when_subject_matches_conventional_commits', async (t) => {
  const { repo } = await makeRepo(t);
  const r = commitMsg(repo, await writeMsg(repo, 'feat(app): 增加手机号验证码登录'));
  assert.equal(r.status, 0, r.stderr);
});

test('should_reject_when_type_not_in_whitelist', async (t) => {
  const { repo } = await makeRepo(t);
  const r = commitMsg(repo, await writeMsg(repo, 'update: 修改了一些东西'));
  assert.equal(r.status, 1);
  assert.match(r.stderr, /首行须为/);
});

test('should_reject_when_subject_exceeds_50_chars', async (t) => {
  const { repo } = await makeRepo(t);
  const long = '很'.repeat(51);
  const r = commitMsg(repo, await writeMsg(repo, `feat(app): ${long}`));
  assert.equal(r.status, 1);
  assert.match(r.stderr, /50 字/);
});

test('should_reject_when_subject_ends_with_sentence_period', async (t) => {
  const { repo } = await makeRepo(t);
  const r = commitMsg(repo, await writeMsg(repo, 'feat(app): 增加登录。'));
  assert.equal(r.status, 1);
  assert.match(r.stderr, /句号/);
});

test('should_exempt_merge_revert_fixup_subjects', async (t) => {
  const { repo } = await makeRepo(t);
  for (const subject of ['Merge branch \'feat/x\'', 'Revert "feat(a): 旧提交"', 'fixup! feat(a): 旧提交', 'squash! feat(a): 旧提交']) {
    const r = commitMsg(repo, await writeMsg(repo, subject));
    assert.equal(r.status, 0, `${subject} 应豁免：${r.stderr}`);
  }
});

test('should_exempt_when_merge_is_in_progress', async (t) => {
  const { repo, g } = await makeRepo(t);
  await writeFile(join(repo, '.git', 'MERGE_HEAD'), `${g(['rev-parse', 'HEAD']).stdout.trim()}\n`, 'utf8');
  const r = commitMsg(repo, await writeMsg(repo, '不合规范的临时合并信息'));
  assert.equal(r.status, 0, r.stderr);
});

// ---- pre-push ----

test('should_reject_when_push_target_is_main', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { forward } = await prepareHistory(repo, g);
  const r = prePush(repo, `refs/heads/main ${forward} refs/heads/main ${ZERO}\n`);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /main 受保护/);
});

test('should_reject_when_deleting_main', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { base } = await prepareHistory(repo, g);
  const r = prePush(repo, `(delete) ${ZERO} refs/heads/main ${base}\n`);
  assert.equal(r.status, 1);
});

test('should_pass_when_push_is_fast_forward', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { base, forward } = await prepareHistory(repo, g);
  const r = prePush(repo, `refs/heads/dev ${forward} refs/heads/dev ${base}\n`);
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

test('should_reject_when_push_is_not_fast_forward', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { sibling, forward } = await prepareHistory(repo, g);
  const r = prePush(repo, `refs/heads/dev ${forward} refs/heads/dev ${sibling}\n`);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /非快进/);
});

test('should_reject_if_any_ref_in_multi_ref_push_fails', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { base, sibling, forward } = await prepareHistory(repo, g);
  const input = [
    `refs/heads/dev ${forward} refs/heads/dev ${sibling}`, // 非快进 → 拒绝
    `refs/heads/dev2 ${forward} refs/heads/dev2 ${base}`, // 快进 → 通过
  ].join('\n') + '\n';
  const r = prePush(repo, input);
  assert.equal(r.status, 1);
});

test('should_pass_when_deleting_remote_feature_branch', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { base } = await prepareHistory(repo, g);
  const r = prePush(repo, `(delete) ${ZERO} refs/heads/feat/1-old ${base}\n`);
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

// ---- enable.mjs ----

test('should_enable_hooks_path_and_self_check_when_hooks_present', async (t) => {
  const { repo } = await makeRepo(t, { withHooks: true });
  const r = spawnSync('node', [join(HOOKS, 'enable.mjs')], { cwd: repo, encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /hooksPath=\.ai\/hooks/);
  const cfg = await readFile(join(repo, '.git', 'config'), 'utf8');
  assert.match(cfg, /hooksPath = \.ai\/hooks/);
});

test('should_fail_enable_when_hooks_missing', async (t) => {
  const { repo } = await makeRepo(t);
  // 沙箱 repo 未放置 .ai/hooks，自检应失败并退出码 1
  const r = spawnSync('node', [join(HOOKS, 'enable.mjs')], { cwd: repo, encoding: 'utf8' });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /启用失败/);
});
