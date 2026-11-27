import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { lstat, mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// code-review 蜂群统一执行器的配置引擎回归：静态源码断言 + 临时目录行为验证。
// 这是"评审流程不含业务测试"约束下的配置工程测试，不评审任何业务代码、不写仓库内产物。

const root = fileURLToPath(new URL('../../', import.meta.url));
const RUNNER = '.ai/skills/code-review/scripts/run.mjs';
const runnerSource = () => readFile(join(root, RUNNER), 'utf8');
// 静态断言针对可执行代码；shebang 的 /usr/bin/env 不是硬编码业务路径。
const runnerCode = async () => (await runnerSource()).replace(/^#![^\n]*\n/, '');

async function sandboxFor(t) {
  const temp = join(root, '.ai-tmp');
  const tempStat = await lstat(temp);
  assert.ok(tempStat.isDirectory() && !tempStat.isSymbolicLink(), 'fixtures must stay in repository .ai-tmp/');
  // --cleanup 白名单要求目标位于 .ai-tmp/code-review/ 之下，fixture 随之落在该评审根内。
  const reviewRoot = join(temp, 'code-review');
  await mkdir(reviewRoot, { recursive: true });
  const sandbox = await mkdtemp(join(reviewRoot, 'scripts-test-'));
  t.after(() => rm(sandbox, { recursive: true, force: true }));
  return sandbox;
}

function runRunner(args, env = process.env) {
  const result = spawnSync(process.execPath, [join(root, RUNNER), ...args], {
    cwd: root,
    env,
    encoding: 'utf8',
    timeout: 15000,
  });
  assert.ifError(result.error);
  return result;
}

test('should_execute_only_through_execfile_with_shell_disabled', async () => {
  const src = await runnerCode();
  assert.match(src, /import\s*\{\s*execFile\s*\}\s*from\s*'node:child_process'/, '必须导入 execFile');
  assert.match(src, /execFile\([^;]*\{\s*shell:\s*false\s*\}/, 'execFile 必须显式 shell:false');
  assert.doesNotMatch(src, /shell\s*:\s*true/, '禁止 shell 真值');
  assert.doesNotMatch(src, /\b(?:exec|execSync|spawn|spawnSync)\s*\(/, '禁止 exec/spawn 家族');
  assert.doesNotMatch(src, /'[^'\n]*&&[^'\n]*'|`[^`\n]*&&[^`\n]*`|"[^"\n]*&&[^"\n]*"/, '禁止字符串内的命令串联');
  assert.doesNotMatch(src, /'[^'\n]*\|[^'\n]*'|`[^`\n]*\|[^`\n]*`|"[^"\n]*\|[^"\n]*"/, '禁止字符串内的管道语法');
  assert.doesNotMatch(src, /execFile\([^)]*\$\{/, '禁止在 execFile 调用中拼字符串');
  assert.match(src, /process\.execPath/, 'Node 路径必须用 process.execPath');
});

test('should_have_no_platform_specific_commands_or_paths', async () => {
  const src = await runnerCode();
  assert.doesNotMatch(src, /process\.platform|win32|darwin|linux/, '禁止按平台分支');
  assert.doesNotMatch(src, /\.(?:cmd|bat|ps1|sh)\b/, '禁止平台脚本后缀文件');
  assert.doesNotMatch(src, /\b(?:grep|sed|findstr|Select-String)\b/, '禁止平台专属文本检索命令');
  assert.doesNotMatch(src, /C:\\|\\usr\\|\/usr\//, '禁止硬编码绝对路径');
  assert.match(src, /import\s*\{\s*resolve\s*\}\s*from\s*'node:path'/, '路径一律经 path.resolve');
  assert.match(src, /resolve\(/, '必须使用 resolve 归一化路径');
});

test('should_resolve_maven_without_platform_branch_and_fail_explicitly', async () => {
  const src = await runnerCode();
  assert.match(src, /process\.env\.ACME_MVN/, '优先读取环境变量 ACME_MVN');
  assert.match(src, /'请设置 ACME_MVN/, '缺失时必须明确报错「请设置 ACME_MVN」');
  assert.match(src, /'mvn'/, '其次交给 PATH 解析 mvn');
  const result = runRunner(['--mvn', '-v'], { ...process.env, ACME_MVN: '', PATH: '' });
  assert.notEqual(result.status, 0, result.stdout);
  assert.match(result.stderr, /请设置 ACME_MVN/, result.stderr);
});

test('should_cleanup_probe_fixture_through_fs_rm', async (t) => {
  const sandbox = await sandboxFor(t);
  const target = join(sandbox, 'probe-target');
  await mkdir(target, { recursive: true });
  await writeFile(join(target, 'marker.txt'), 'probe', 'utf8');
  const cleaned = runRunner(['--cleanup', target]);
  assert.equal(cleaned.status, 0, cleaned.stderr);
  await assert.rejects(stat(target), { code: 'ENOENT' }, '临时探针目录应被删除');

  // 探针本身以 fs.rm 清理，且必须经统一执行器用 node 执行（不经 shell）。
  const probe = join(sandbox, 'probe.mjs');
  const probeSource = [
    "import { mkdir, rm, writeFile } from 'node:fs/promises';",
    "import { join } from 'node:path';",
    "const dir = join(process.argv[2], 'probe-target');",
    "await mkdir(dir, { recursive: true });",
    "await writeFile(join(dir, 'marker.txt'), 'probe', 'utf8');",
    'await rm(dir, { recursive: true, force: true });',
    "console.log('CLEANED:' + dir);",
  ].join('\n');
  await writeFile(probe, probeSource, 'utf8');
  const src = await runnerCode();
  assert.match(src, /import\s*\{\s*rm\s*\}\s*from\s*'node:fs\/promises'/, '清理必须导入 fs.rm');
  assert.match(src, /rm\(resolve\([^)]*\),\s*\{\s*recursive:\s*true,\s*force:\s*true\s*\}/, '清理必须 recursive+force');
  assert.doesNotMatch(src, /rm\s+-rf/, '禁止递归强删命令');
  const result = runRunner([probe, sandbox]);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /CLEANED:/, result.stdout);
  await assert.rejects(stat(join(sandbox, 'probe-target')), { code: 'ENOENT' });
});

// ---- 能力升级：--timeout 结构化 SKIP / --cleanup 白名单 / l1-checks 零依赖入口 ----
test('should_accept_timeout_before_subcommand_and_report_structured_skip', async (t) => {
  const src = await runnerCode();
  assert.match(src, /--timeout/, 'run.mjs 应解析 --timeout');
  assert.match(src, /execFile\([^;]*timeout/, 'execFile 应传 timeout 选项');
  assert.match(src, /SKIP\(超时/, '超时应输出结构化 SKIP');
  const sandbox = await sandboxFor(t);
  const probe = join(sandbox, 'slow.mjs');
  await writeFile(probe, "setTimeout(() => { console.log('DONE'); }, 5000);", 'utf8');
  const result = runRunner(['--timeout', '50', probe]);
  assert.notEqual(result.status, 0, '超时应非零退出');
  assert.match(result.stdout + result.stderr, /SKIP\(超时 50ms/, result.stdout + result.stderr);
});

test('should_reject_cleanup_outside_whitelisted_review_subdirectory', async () => {
  const outside = join(root, '.ai-tmp', 'not-code-review');
  const first = runRunner(['--cleanup', outside]);
  assert.notEqual(first.status, 0, '白名单外清理应失败');
  assert.match(first.stderr, /越权清理/, first.stderr);

  const sharedRoot = join(root, '.ai-tmp', 'code-review');
  const second = runRunner(['--cleanup', sharedRoot]);
  assert.notEqual(second.status, 0, '共享评审根目录不可清理');
  assert.match(second.stderr, /越权清理/, second.stderr);
});

test('should_run_l1_checks_only_through_pnpm_cli_without_new_dependencies', async () => {
  // shebang 的 /usr/bin/env 不是硬编码业务路径，与 runnerCode() 同口径剥离后校验。
  const src = (await readFile(join(root, '.ai/skills/code-review/scripts/l1-checks.mjs'), 'utf8')).replace(/^#![^\n]*\n/, '');
  assert.match(src, /import\s*\{\s*execFile\s*\}\s*from\s*'node:child_process'/, '必须导入 execFile');
  assert.match(src, /execFile\(process\.execPath,\s*\[pnpmCli, \.\.\.args\],\s*\{\s*shell:\s*false\s*\}/, '必须用 execFile 直传 pnpm CLI');
  assert.doesNotMatch(src, /shell\s*:\s*true/, '禁止 shell 真值');
  assert.match(src, /'pnpm', 'bin', 'pnpm\.cjs'/, '应从 PATH 扫描 pnpm.cjs 入口');
  assert.match(src, /delimiter/, 'PATH 分隔应跨平台');
  assert.doesNotMatch(src, /process\.platform|win32|darwin|linux/, '禁止按平台分支');
  assert.doesNotMatch(src, /C:\\|\\usr\\|\/usr\//, '禁止硬编码绝对路径');
  for (const subcommand of ['desktop-check', 'theme-tests', 'test-shard', 'build']) {
    assert.ok(src.includes(subcommand), `l1-checks 应支持 ${subcommand}`);
  }
  assert.match(src, /不新增依赖/, '应声明不新增依赖');
  const result = runRunner(['.ai/skills/code-review/scripts/l1-checks.mjs', 'unknown-cmd']);
  assert.notEqual(result.status, 0, '未知子命令应报错');
  assert.match(result.stderr, /用法/, result.stderr);
});
