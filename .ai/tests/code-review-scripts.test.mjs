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
  const sandbox = await mkdtemp(join(temp, 'code-review-scripts-test-'));
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
