import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const tempRoot = join(root, '.ai-tmp');
const HOOKS = join(root, '.ai/hooks');
const ZERO = '0'.repeat(40);

// 沙箱固定落在仓库 .ai-tmp/（不入库）；每个用例独占目录，用例结束即清理。
async function makeRepo(t, { withHooks = false, sha256 = false } = {}) {
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
  g(['init', '-q', ...(sha256 ? ['--object-format=sha256'] : [])]);
  g(['config', 'user.email', 'hook-test@example.com']);
  g(['config', 'user.name', 'hook-test']);
  g(['checkout', '-q', '-b', 'main']); // 沙箱 init 默认分支可能是 master，显式对齐受保护分支名
  g(['commit', '--allow-empty', '-m', 'chore: base']); // C1
  return { repo, g };
}

/** 跨平台解析 POSIX shell：要求 sh 与 coreutils（dirname 等）齐备——真实 git 钩子链路会注入
 *  /usr/bin，而直启 sh.exe 时 msys 转换后的 PATH 不含它（Windows 实测，2026-11-27 体检 #4），
 *  pre-push 的 `dirname "$0"` 会失效。先探测 PATH 上的 sh（连带验证 dirname 在位）；失败时从
 *  git --exec-path 反推 Git 自带 usr/bin/sh，并返回其目录供 spawn 时补进 PATH（等价还原真实链路）。 */
function shellForHooks() {
  const probe = spawnSync('sh', ['-c', 'command -v dirname >/dev/null 2>&1']);
  if (!probe.error && probe.status === 0) return { shell: 'sh', dir: null };
  const execPath = spawnSync('git', ['--exec-path'], { encoding: 'utf8' });
  if (!execPath.error && execPath.status === 0) {
    const dir = resolve(execPath.stdout.trim(), '../../../usr/bin');
    if (existsSync(join(dir, 'sh'))) return { shell: join(dir, 'sh'), dir };
    if (existsSync(`${join(dir, 'sh')}.exe`)) return { shell: `${join(dir, 'sh')}.exe`, dir };
  }
  return { shell: 'sh', dir: null }; // 保留原始 ENOENT 便于诊断
}
const { shell: HOOK_SHELL, dir: HOOK_USR_BIN } = shellForHooks();
/** 直启 sh.exe（绕过 git 钩子执行链路）时补齐真实链路注入的环境：/usr/bin 进 PATH；
 *  POSIX 上 dir 为 null，env 原样传递（CI 行为不变）。 */
const HOOK_ENV = HOOK_USR_BIN
  ? (() => {
      const key = Object.keys(process.env).find((k) => k.toUpperCase() === 'PATH') || 'PATH';
      const sep = process.platform === 'win32' ? ';' : ':';
      return { ...process.env, [key]: `${HOOK_USR_BIN}${sep}${process.env[key] || ''}` };
    })()
  : process.env;
/** 钩子脚本路径转正斜杠：$0 要经 dirname 解析，msys 的 dirname 只认 `/` 分隔
 *  （Windows 反斜杠路径会得到 `.`，导致 hookdir 错位）。 */
const posix = (p) => p.split('\\').join('/');

const commitMsg = (repo, msgFile) =>
  spawnSync(HOOK_SHELL, [posix(join(HOOKS, 'commit-msg')), msgFile], { cwd: repo, encoding: 'utf8', env: HOOK_ENV });
const prePush = (repo, input, env) =>
  spawnSync(HOOK_SHELL, [posix(join(HOOKS, 'pre-push'))], {
    cwd: repo,
    encoding: 'utf8',
    input,
    env: env ? { ...HOOK_ENV, ...env } : HOOK_ENV,
  });

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

// ---- pre-commit ----

test('should_cover_ai_memory_in_precommit_staging_list_consistent_with_mirror', async (t) => {
  // 口径统一回归（ADR 2026-11-14-22、2026-11-17-01）：暂存清单唯一事实源为 .ai/hooks/ai-paths.list，
  // pre-commit 必须逐行读取该文件（清单与 mirror-check/CI mirror-sync 同口径由 sync.mjs + ai-paths.test.mjs 锁定）；
  // iFlow 已裁撤（ADR 2026-11-14-21），localOnly 本机目标（.zcode/.codebuddy）不入清单。
  const source = await readFile(join(HOOKS, 'pre-commit'), 'utf8');
  assert.match(source, /ai-paths\.list/, 'pre-commit 应读取 .ai/hooks/ai-paths.list（AI 路径单一清单）');
  assert.match(source, /while IFS= read -r line/, 'pre-commit 应逐行读取清单文件');
  const list = (await readFile(join(HOOKS, 'ai-paths.list'), 'utf8'))
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'));
  assert.ok(list.length >= 15, `清单应含全部既有路径（当前 ${list.length} 条）`);
  assert.ok(list.includes('.ai-memory'), '清单应包含 .ai-memory（与镜像口径一致）');
  assert.doesNotMatch(list.join('\n'), /\.iflow/, '清单不应包含已裁撤的 .iflow');
  assert.doesNotMatch(list.join('\n'), /\.zcode|\.codebuddy/, '清单不应包含 localOnly 本机目标（不入库）');
});

// ---- pre-push ----

test('should_reject_when_push_target_is_main', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { forward } = await prepareHistory(repo, g);
  const r = prePush(repo, `refs/heads/main ${forward} refs/heads/main ${ZERO}\n`);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /main 已归档/);
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

test('should_reject_when_creating_remote_feature_branch', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { forward } = await prepareHistory(repo, g);
  const r = prePush(repo, `refs/heads/feat/1-new ${forward} refs/heads/feat/1-new ${ZERO}\n`);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /只留本地/);
});

test('should_allow_feature_branch_push_when_explicitly_allowed', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { forward } = await prepareHistory(repo, g);
  const r = prePush(repo, `refs/heads/fix/1-new ${forward} refs/heads/fix/1-new ${ZERO}\n`, {
    ACME_ALLOW_BRANCH_PUSH: '1',
  });
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

test('should_reject_when_deleting_policy_branch', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { base } = await prepareHistory(repo, g);
  const r = prePush(repo, `(delete) ${ZERO} refs/heads/ai-coding-support ${base}\n`);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /不允许删除/);
});

test('should_reject_when_deleting_policy_branch_in_sha256_repo', async (t) => {
  // SHA-256 回归（遗留项 2026-11-14-#3）：零值 SHA 不再硬编码 40 位——64 位零值同样触发策略分支删除拦截
  const { repo, g } = await makeRepo(t, { sha256: true });
  const { base } = await prepareHistory(repo, g);
  const r = prePush(repo, `(delete) ${'0'.repeat(64)} refs/heads/ai-coding-support ${base}\n`);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /不允许删除/);
});

test('should_pass_when_deleting_remote_feature_branch_in_sha256_repo', async (t) => {
  // SHA-256 回归（遗留项 2026-11-14-#3）：任务分支合法删除在 64 位零值下必须放行（旧 40 位写死会误判为更新、落入非快进报错）
  const { repo, g } = await makeRepo(t, { sha256: true });
  const { base } = await prepareHistory(repo, g);
  const r = prePush(repo, `(delete) ${'0'.repeat(64)} refs/heads/feat/1-old ${base}\n`);
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

// 在 sandbox 内造出两条策略分支，diverge=true 时让 ai-coding-support 的 AI 路径与主线不同。
async function makeMirrorHistory(repo, g, { diverge }) {
  await mkdir(join(repo, '.ai/rules'), { recursive: true });
  await writeFile(join(repo, '.ai/rules/sample.md'), 'A\n', 'utf8');
  g(['add', '.ai']);
  g(['commit', '-q', '-m', 'chore: ai a']);
  const acme = g(['rev-parse', 'HEAD']).stdout.trim();
  g(['checkout', '-q', '-b', 'ai-coding-support']);
  if (diverge) {
    await writeFile(join(repo, '.ai/rules/sample.md'), 'B\n', 'utf8');
    g(['add', '.ai']);
    g(['commit', '-q', '-m', 'chore: ai b']);
  }
  const support = g(['rev-parse', 'HEAD']).stdout.trim();
  g(['checkout', '-q', 'main']);
  return { acme, support };
}

test('should_reject_when_only_one_strategy_branch_pushed', async (t) => {
  // review 修复回归：本地已镜像但只推一条策略分支 → 本地预检必过而远端对端未更新（CI mirror-sync 红灯）
  const { repo, g } = await makeRepo(t);
  const { acme } = await makeMirrorHistory(repo, g, { diverge: false });
  const r = prePush(repo, `refs/heads/acme-foundation ${acme} refs/heads/acme-foundation ${ZERO}\n`);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /同一条命令/);
});

test('should_reject_when_only_support_branch_pushed', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { acme } = await makeMirrorHistory(repo, g, { diverge: false });
  const r = prePush(repo, `refs/heads/ai-coding-support ${acme} refs/heads/ai-coding-support ${ZERO}\n`);
  assert.equal(r.status, 1);
  assert.match(r.stdout, /同一条命令/);
});

test('should_reject_policy_push_when_ai_config_mirror_differs', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { acme, support } = await makeMirrorHistory(repo, g, { diverge: true });
  const input = [
    `refs/heads/acme-foundation ${acme} refs/heads/acme-foundation ${ZERO}`,
    `refs/heads/ai-coding-support ${support} refs/heads/ai-coding-support ${ZERO}`,
  ].join('\n') + '\n';
  const r = prePush(repo, input);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /镜像/);
});

test('should_pass_policy_push_when_ai_config_mirror_matches', async (t) => {
  const { repo, g } = await makeRepo(t);
  const { acme, support } = await makeMirrorHistory(repo, g, { diverge: false });
  const input = [
    `refs/heads/acme-foundation ${acme} refs/heads/acme-foundation ${ZERO}`,
    `refs/heads/ai-coding-support ${support} refs/heads/ai-coding-support ${ZERO}`,
  ].join('\n') + '\n';
  const r = prePush(repo, input);
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
