import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFile, lstat, mkdir, mkdtemp, readFile, readdir, readlink, rm, symlink, writeFile } from 'node:fs/promises';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const tempRoot = join(root, '.ai-tmp');
const rule = '# Fixture rules\n\nKeep generated files in sync.\n';

async function put(base, rel, content) {
  const file = join(base, rel);
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, content, 'utf8');
}

async function fixture(t) {
  const tempStat = await lstat(tempRoot);
  assert.ok(tempStat.isDirectory() && !tempStat.isSymbolicLink(), 'fixtures must stay in repository .ai-tmp/');
  const sandbox = await mkdtemp(join(tempRoot, 'sync-test-'));
  // Only remove this test's exclusive sandbox, never the shared temporary root.
  t.after(() => rm(sandbox, { recursive: true, force: true }));
  const repo = join(sandbox, 'repo');
  const outside = join(sandbox, 'outside');
  await mkdir(join(repo, '.ai'), { recursive: true });
  await mkdir(outside);
  await copyFile(join(root, '.ai/sync.mjs'), join(repo, '.ai/sync.mjs'));
  await put(repo, '.ai/rules/01.md', rule);
  return { repo, outside };
}

function run(repo, args = [], { preload, linkEnv = '0' } = {}) {
  const nodeArgs = preload ? ['--import', `data:text/javascript,${encodeURIComponent(preload)}`] : [];
  const result = spawnSync(process.execPath, [...nodeArgs, join(repo, '.ai/sync.mjs'), ...args], {
    cwd: repo,
    env: { ...process.env, NODE_OPTIONS: '', AI_SYNC_LINK: linkEnv },
    encoding: 'utf8',
    timeout: 15000,
  });
  assert.ifError(result.error);
  return result;
}

function succeeds(result) {
  assert.equal(result.status, 0, result.stderr || result.stdout);
}

function fails(result, message) {
  assert.notEqual(result.status, 0, result.stdout);
  assert.match(result.stderr, message);
}

async function snapshot(base, dir = base) {
  const result = [];
  for (const entry of (await readdir(dir)).sort()) {
    const file = join(dir, entry);
    const rel = relative(base, file).split('\\').join('/');
    const st = await lstat(file);
    if (st.isSymbolicLink()) result.push([rel, 'link', await readlink(file), st.mtimeMs]);
    else if (st.isDirectory()) {
      result.push([rel, 'directory', st.mtimeMs]);
      result.push(...await snapshot(base, file));
    } else result.push([rel, 'file', (await readFile(file)).toString('hex'), st.mtimeMs]);
  }
  return result;
}

async function makeLink(t, target, link, directory = false) {
  await mkdir(dirname(link), { recursive: true });
  try {
    await symlink(target, link, directory ? (process.platform === 'win32' ? 'junction' : 'dir') : 'file');
    return true;
  } catch (error) {
    if (!['EPERM', 'EACCES', 'ENOSYS', 'ENOTSUP', 'EOPNOTSUPP'].includes(error.code)) throw error;
    t.skip(`symlink unavailable: ${error.code}`);
    return false;
  }
}

// Observe attempted mutations, including probes that are immediately deleted or errors caught by sync.
const noWrites = `
  import fs from 'node:fs/promises';
  import { syncBuiltinESMExports } from 'node:module';
  let attempted = false;
  for (const name of ['writeFile', 'appendFile', 'mkdir', 'mkdtemp', 'symlink', 'unlink', 'rm', 'rmdir',
    'rename', 'copyFile', 'cp', 'link', 'truncate', 'open']) {
    fs[name] = async () => {
      attempted = true;
      console.error('WRITE_ATTEMPT:' + name);
      throw new Error('check must not write');
    };
  }
  syncBuiltinESMExports();
  process.on('exit', () => { if (attempted) process.exitCode = 99; });
`;

test('should_sync_and_check_minimal_safe_sources_without_changing_formats', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, '.ai/rules/nested/02.md', '# Nested\r\n\r\nSecond rule.\r\n');
  await put(repo, '.ai/scopes/project/AGENTS.md', '# Project scope\n');
  await putSwarm(repo, 'ai-coding-dev');
  await put(repo, '.ai/agents/ai-coding-dev/helper.md', '# Helper\n');
  await put(repo, '.ai/agents/AGENT-POOL.md', 'Source-only documentation\n');
  await put(repo, '.ai/skills/review/SKILL.md', '# Review\n');
  await put(repo, '.ai/commands/review.md', '# Review command\n');
  await put(repo, '.ai/tools/claude/CLAUDE-CODE.md', '# Claude directory\n');
  succeeds(run(repo));
  const combined = await readFile(join(repo, '.ai/RULES.md'), 'utf8');
  assert.ok(combined.endsWith(rule.trim() + '\n\n# Nested\n\nSecond rule.\n'));
  assert.match(await readFile(join(repo, 'CLAUDE.md'), 'utf8'), /@\.ai\/RULES\.md/);
  assert.match(await readFile(join(repo, 'project/CLAUDE.md'), 'utf8'), /^@AGENTS\.md\n/);
  assert.match(await readFile(join(repo, '.claude/agents/helper.md'), 'utf8'), /派生自 \.ai\/agents\/ai-coding-dev\/helper\.md/);
  await assert.rejects(lstat(join(repo, '.claude/agents/AGENT-POOL.md')), { code: 'ENOENT' });
  const before = await snapshot(repo);
  const checked = run(repo, ['--check']);
  succeeds(checked);
  assert.match(checked.stdout, /校验通过.*无孤儿残留/);
  assert.deepEqual(await snapshot(repo), before);
  succeeds(run(repo));
  succeeds(run(repo, ['--check']));
});

test('should_exclude_non_md_files_from_rules', async (t) => {
  const { repo } = await fixture(t);
  succeeds(run(repo));
  const expected = await readFile(join(repo, '.ai/RULES.md'), 'utf8');
  await put(repo, '.ai/rules/ignored.txt', 'NON_MD_SENTINEL'.repeat(1000));
  await put(repo, '.ai/rules/nested/ignored.json', '{"ignored":true}\n');
  succeeds(run(repo));
  assert.equal(await readFile(join(repo, '.ai/RULES.md'), 'utf8'), expected);
  succeeds(run(repo, ['--check']));
});

test('should_enforce_final_utf8_budget_before_any_write_including_link_probe', async (t) => {
  const { repo } = await fixture(t);
  succeeds(run(repo));
  const combined = await readFile(join(repo, '.ai/RULES.md'), 'utf8');
  const overhead = Buffer.byteLength(combined) - Buffer.byteLength(rule.trim());
  const available = 8192 - overhead;
  const exact = '界'.repeat(Math.floor(available / 3)) + 'a'.repeat(available % 3);
  await put(repo, '.ai/rules/01.md', exact);
  succeeds(run(repo));
  assert.equal((await readFile(join(repo, '.ai/RULES.md'))).length, 8192);
  succeeds(run(repo, ['--check']));
  await put(repo, '.ai/rules/01.md', exact + '界');
  const before = await snapshot(repo);
  for (const args of [[], ['--links'], ['--check', '--links']]) {
    fails(run(repo, args), /8195 bytes > 8192 bytes/);
    assert.deepEqual(await snapshot(repo), before);
  }
});

const conflicts = [
  {
    name: 'pool_and_private_rule_share_target',
    sources: ['.ai/agents/ai-coding-dev/helper.md', '.ai/tools/claude/agents/helper.md'],
    target: '.claude/agents/helper.md',
    swarms: ['ai-coding-dev'],
  },
  {
    name: 'private_rule_overrides_public_entry',
    sources: ['.ai/tools/qoder/rules/project_rules.md'],
    target: '.qoder/rules/project_rules.md',
    publicEntry: true,
  },
  {
    name: 'scope_overrides_root_link_entry',
    sources: ['.ai/scopes/AGENTS.md'],
    target: 'AGENTS.md',
    publicEntry: true,
  },
  {
    name: 'scope_content_conflicts_with_generated_link',
    sources: ['.ai/scopes/project/AGENTS.md', '.ai/scopes/project/CLAUDE.md'],
    target: 'project/CLAUDE.md',
  },
  {
    name: 'targets_differ_only_in_windows_case',
    sources: ['.ai/agents/ai-coding-dev/helper.md', '.ai/tools/claude/agents/HELPER.md'],
    target: '.claude/agents/HELPER.md',
    swarms: ['ai-coding-dev'],
  },
  {
    name: 'content_and_link_differ_only_in_windows_case',
    sources: ['.ai/scopes/claude.md'],
    target: 'claude.md',
    publicEntry: true,
  },
];
for (const conflict of conflicts) {
  test(`should_reject_conflicts_before_writing_when_${conflict.name}`, async (t) => {
    const { repo } = await fixture(t);
    for (const name of conflict.swarms ?? []) await putSwarm(repo, name);
    for (const source of conflict.sources) await put(repo, source, '# Conflicting source\n');
    const before = await snapshot(repo);
    for (const args of [['--links'], ['--check']]) {
      const result = run(repo, args);
      fails(result, /派生目标冲突/);
      for (const source of conflict.sources) assert.ok(result.stderr.includes(source), result.stderr);
      assert.ok(result.stderr.includes(conflict.target), result.stderr);
      if (conflict.publicEntry) assert.ok(result.stderr.includes('.ai/rules/*.md（公共入口）'), result.stderr);
      assert.deepEqual(await snapshot(repo), before);
    }
  });
}

for (const target of ['AGENTS.md', '.cursor/rules/project.mdc']) {
  test(`should_preserve_directory_at_output_${target}`, async (t) => {
    const { repo } = await fixture(t);
    await put(repo, `${target}/keep.txt`, 'Do not delete this directory.\n');
    const before = await snapshot(repo);
    for (const args of [[], ['--links'], ['--check']]) {
      fails(run(repo, args), /目录不会删除/);
      assert.deepEqual(await snapshot(repo), before);
    }
  });
}

test('should_reject_regular_file_in_output_parent_path', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, '.cursor/rules', 'Do not replace this file.\n');
  const before = await snapshot(repo);
  fails(run(repo), /路径不是目录/);
  assert.deepEqual(await snapshot(repo), before);
});

test('should_not_follow_external_content_output_link', async (t) => {
  const { repo, outside } = await fixture(t);
  await put(outside, 'keep.txt', 'External data\n');
  if (!await makeLink(t, join(outside, 'keep.txt'), join(repo, '.cursor/rules/project.mdc'))) return;
  const before = await snapshot(repo);
  for (const args of [[], ['--check']]) {
    fails(run(repo, args), /拒绝符号链接路径/);
    assert.equal(await readFile(join(outside, 'keep.txt'), 'utf8'), 'External data\n');
    assert.deepEqual(await snapshot(repo), before);
  }
});

test('should_not_follow_external_output_parent_link', async (t) => {
  const { repo, outside } = await fixture(t);
  await put(outside, 'rules/project.mdc', 'External data\n');
  if (!await makeLink(t, outside, join(repo, '.cursor'), true)) return;
  const before = await snapshot(repo);
  const outsideBefore = await snapshot(outside);
  for (const args of [[], ['--links'], ['--check']]) {
    fails(run(repo, args), /拒绝符号链接路径/);
    assert.deepEqual(await snapshot(repo), before);
    assert.deepEqual(await snapshot(outside), outsideBefore);
  }
});

test('should_unlink_root_entry_without_overwriting_its_external_target', async (t) => {
  const { repo, outside } = await fixture(t);
  await put(outside, 'keep.txt', 'External data\n');
  if (!await makeLink(t, join(outside, 'keep.txt'), join(repo, 'AGENTS.md'))) return;
  succeeds(run(repo));
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), false);
  assert.equal(await readFile(join(outside, 'keep.txt'), 'utf8'), 'External data\n');
  succeeds(run(repo, ['--check']));
});

for (const directory of [false, true]) {
  test(`should_not_read_external_source_${directory ? 'directory' : 'file'}_link`, async (t) => {
    const { repo, outside } = await fixture(t);
    await put(outside, 'external.md', '# External source must not be read\n');
    const source = directory ? '.ai/commands' : '.ai/rules/external.md';
    const target = directory ? outside : join(outside, 'external.md');
    if (!await makeLink(t, target, join(repo, source), directory)) return;
    const before = await snapshot(repo);
    for (const args of [[], ['--check']]) {
      fails(run(repo, args), /拒绝符号链接/);
      assert.deepEqual(await snapshot(repo), before);
    }
  });
}

test('should_surface_walk_permission_errors_instead_of_treating_them_as_missing', async (t) => {
  const { repo } = await fixture(t);
  const before = await snapshot(repo);
  const preload = `
    import fs from 'node:fs/promises';
    import { syncBuiltinESMExports } from 'node:module';
    const original = fs.readdir;
    fs.readdir = async (dir, ...args) => {
      if (String(dir).replaceAll('\\\\', '/').endsWith('/rules')) {
        throw Object.assign(new Error('fixture EACCES: cannot read rules'), { code: 'EACCES' });
      }
      return original(dir, ...args);
    };
    syncBuiltinESMExports();
  `;
  fails(run(repo, [], { preload }), /fixture EACCES: cannot read rules/);
  assert.deepEqual(await snapshot(repo), before);
});

test('should_keep_check_links_read_only_for_synced_and_missing_outputs', async (t) => {
  const { repo } = await fixture(t);
  const before = await snapshot(repo);
  const missing = run(repo, ['--check', '--links'], { preload: noWrites });
  fails(missing, /缺失/);
  assert.notEqual(missing.status, 99, missing.stderr);
  assert.doesNotMatch(missing.stderr, /WRITE_ATTEMPT/);
  assert.deepEqual(await snapshot(repo), before);
  succeeds(run(repo));
  const synced = await snapshot(repo);
  succeeds(run(repo, ['--check', '--links'], { preload: noWrites }));
  succeeds(run(repo, ['--check'], { preload: noWrites, linkEnv: '1' }));
  assert.deepEqual(await snapshot(repo), synced);
});

test('should_probe_only_repository_temp_and_clean_up_when_links_are_unavailable', async (t) => {
  const { repo } = await fixture(t);
  const preload = `
    import fs from 'node:fs/promises';
    import { relative, isAbsolute, sep } from 'node:path';
    import { syncBuiltinESMExports } from 'node:module';
    const temp = ${JSON.stringify(join(repo, '.ai-tmp'))};
    fs.symlink = async (target, file) => {
      const rel = relative(temp, file);
      if (isAbsolute(rel) || rel === '..' || rel.startsWith('..' + sep)) {
        throw new Error('PROBE_OUTSIDE_REPOSITORY:' + file);
      }
      console.error('PROBE_INSIDE_REPOSITORY');
      throw Object.assign(new Error('fixture has no link privilege'), { code: 'EPERM' });
    };
    syncBuiltinESMExports();
  `;
  const result = run(repo, ['--links'], { preload });
  succeeds(result);
  assert.match(result.stderr, /PROBE_INSIDE_REPOSITORY/);
  assert.deepEqual(await readdir(join(repo, '.ai-tmp')), []);
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), false);
  succeeds(run(repo, ['--check', '--links'], { preload: noWrites }));
});

test('should_sync_real_links_and_check_without_probe_when_links_are_available', async (t) => {
  const { repo, outside } = await fixture(t);
  await put(outside, 'target.md', '# Safe target\n');
  if (!await makeLink(t, join(outside, 'target.md'), join(outside, 'probe.md'))) return;
  await put(repo, '.ai/scopes/project/AGENTS.md', '# Scope\n');
  succeeds(run(repo));
  succeeds(run(repo, ['--links']));
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), true);
  assert.equal((await readlink(join(repo, 'project/CLAUDE.md'))).split('\\').join('/'), 'AGENTS.md');
  assert.deepEqual(await readdir(join(repo, '.ai-tmp')), []);
  succeeds(run(repo, ['--check', '--links'], { preload: noWrites }));
  succeeds(run(repo));
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), false);
  succeeds(run(repo, ['--check']));
});

// ---- ai-coding-config.yml：引擎开关 + 环境上下文（缺省兼容 / entryMode / orphanDetection / 预算 / environment / 非法配置） ----
const CONFIG = '.ai/ai-coding-config.yml';

test('should_use_stub_defaults_when_config_absent', async (t) => {
  const { repo } = await fixture(t);
  succeeds(run(repo));
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), false);
  succeeds(run(repo, ['--check']));
});

test('should_accept_config_with_comments_and_blank_lines', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, CONFIG, '# header\nversion: 1\n\nengine:\n  entryMode: stub # inline\n  orphanDetection: true\n  rulesBudgetBytes: 8192\n');
  succeeds(run(repo));
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), false);
  succeeds(run(repo, ['--check']));
});

test('should_honor_config_entryMode_symlink_when_links_available', async (t) => {
  const { repo, outside } = await fixture(t);
  await put(outside, 'target.md', '# Safe target\n');
  if (!await makeLink(t, join(outside, 'target.md'), join(outside, 'probe.md'))) return;
  await put(repo, CONFIG, 'version: 1\nengine:\n  entryMode: symlink\n');
  succeeds(run(repo)); // 无 --links，AI_SYNC_LINK=0：配置即默认形态
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), true);
  assert.equal((await readlink(join(repo, 'AGENTS.md'))).split('\\').join('/'), '.ai/RULES.md');
  succeeds(run(repo, ['--check']));
});

test('should_let_cli_links_override_config_entryMode_stub', async (t) => {
  const { repo, outside } = await fixture(t);
  await put(outside, 'target.md', '# Safe target\n');
  if (!await makeLink(t, join(outside, 'target.md'), join(outside, 'probe.md'))) return;
  await put(repo, CONFIG, 'version: 1\nengine:\n  entryMode: stub\n');
  succeeds(run(repo, ['--links']));
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), true);
  succeeds(run(repo, ['--check', '--links']));
});

test('should_fall_back_to_stub_when_config_symlink_but_links_unavailable', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, CONFIG, 'version: 1\nengine:\n  entryMode: symlink\n');
  const preload = `
    import fs from 'node:fs/promises';
    import { syncBuiltinESMExports } from 'node:module';
    fs.symlink = async () => { throw Object.assign(new Error('fixture has no link privilege'), { code: 'EPERM' }); };
    syncBuiltinESMExports();
  `;
  succeeds(run(repo, [], { preload }));
  assert.equal((await lstat(join(repo, 'AGENTS.md'))).isSymbolicLink(), false);
  assert.deepEqual(await readdir(join(repo, '.ai-tmp')), []);
  succeeds(run(repo, ['--check']));
});

test('should_flag_orphan_by_default_and_skip_when_orphanDetection_false', async (t) => {
  const { repo } = await fixture(t);
  succeeds(run(repo));
  await put(repo, '.claude/agents/stale.md', '# Stale\n\n<!-- 派生自 .ai/agents/ai-coding-dev/gone.md -->\n');
  fails(run(repo, ['--check']), /孤儿派生文件/);
  await put(repo, CONFIG, 'version: 1\nengine:\n  orphanDetection: false\n');
  const ok = run(repo, ['--check']);
  succeeds(ok);
  assert.match(ok.stdout, /孤儿检测已关闭/);
});

test('should_map_claude_tool_names_for_gemini_agents_on_derive', async (t) => {
  // Gemini 子代理适配（ADR 2026-11-14-24）：派生到 .gemini/agents 时 frontmatter 工具名映射为 Gemini 内置名，
  // model: inherit 移除（Gemini 缺省即继承）；Claude 目标保持原样；正文不受映射影响。
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  await put(
    repo,
    '.ai/agents/ai-coding-dev/helper.md',
    '---\nname: helper\ndescription: d\ntools: [Read, Write, Edit, Grep, Glob, Bash]\nmodel: inherit\n---\n\nBody mentions Read and Grep words.\n',
  );
  succeeds(run(repo));
  const gemini = await readFile(join(repo, '.gemini/agents/helper.md'), 'utf8');
  assert.match(gemini, /tools: \[read_file, write_file, replace, grep_search, glob, run_shell_command\]/);
  assert.doesNotMatch(gemini, /^model:/m);
  assert.match(gemini, /Body mentions Read and Grep words\./);
  const claude = await readFile(join(repo, '.claude/agents/helper.md'), 'utf8');
  assert.match(claude, /tools: \[Read, Write, Edit, Grep, Glob, Bash\]/);
  succeeds(run(repo, ['--check']));
});

test('should_flag_orphan_derived_files_beyond_tool_dirs', async (t) => {
  // 孤儿检测扩展（审查 2026-11-14）：除 TOOLS 目录外，根部入口与 scopes 派生同级目录的残留也要报。
  const { repo } = await fixture(t);
  await put(repo, '.ai/scopes/project/AGENTS.md', '# Project scope\n');
  succeeds(run(repo));
  // 仓库根：带派生标记的未知残留（非递归扫描，子目录不受影响）
  await put(repo, 'STALE.md', '# leftover\n\n<!-- 派生自 .ai/agents/ai-coding-dev/gone.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->\n');
  fails(run(repo, ['--check']), /孤儿派生文件 STALE\.md/);
  await rm(join(repo, 'STALE.md'));
  // scopes 派生目标的同级目录：带派生标记的残留；无标记的用户文件不受影响
  await put(repo, 'project/STALE.md', '# leftover\n\n<!-- 派生自 .ai/scopes/project/GONE.md -->\n');
  await put(repo, 'project/notes.md', '# user notes, no derived mark\n');
  fails(run(repo, ['--check']), /孤儿派生文件 project\/STALE\.md/);
  await rm(join(repo, 'project/STALE.md'));
  succeeds(run(repo, ['--check']));
});

test('should_mark_script_derived_files_and_flag_them_as_orphans_when_source_removed', async (t) => {
  // P2-A：脚本类派生物（.mjs）此前无派生标记 → 源删除后孤儿检测盲区。派生时插入 // 头注，
  // 但源以 shebang 开头时必须保留其在第 1 行（前置会令 node --check 报 Invalid or unexpected token）。
  const { repo } = await fixture(t);
  await put(repo, '.ai/skills/x/scripts/probe.mjs', '#!/usr/bin/env node\nconsole.log("probe");\n');
  succeeds(run(repo));
  const derivedPath = join(repo, '.claude/skills/x/scripts/probe.mjs');
  const derived = await readFile(derivedPath, 'utf8');
  const lines = derived.split('\n');
  assert.equal(lines[0], '#!/usr/bin/env node', 'shebang 必须留在派生文件首行');
  assert.match(lines[1], /^\/\/ 派生自 \.ai\/skills\/x\/scripts\/probe\.mjs（统一管理于 \.ai\/）/, '第 2 行应是派生标记');
  const checked = spawnSync(process.execPath, ['--check', derivedPath], { encoding: 'utf8' });
  assert.equal(checked.status, 0, checked.stderr);
  succeeds(run(repo, ['--check']));
  // 删源后派生物残留 → --check 应报孤儿（标记可被 DERIVED_MARK 匹配）
  await rm(join(repo, '.ai/skills/x'), { recursive: true });
  fails(run(repo, ['--check']), /孤儿派生文件 \.claude\/skills\/x\/scripts\/probe\.mjs/);
});

test('should_enforce_custom_rulesBudgetBytes_from_config', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, CONFIG, 'version: 1\nengine:\n  rulesBudgetBytes: 200\n');
  const before = await snapshot(repo);
  fails(run(repo), /bytes > 200 bytes/);
  assert.deepEqual(await snapshot(repo), before);
});

// ---- 蜂群（swarm）触发段：三档 / 多蜂群编号 / 键枚举 / 缺省停用 ----
async function putSwarm(repo, name) {
  // 蜂群目录必须自带同名 leader（普通文件），否则 sync 的布局校验会先于用例目标报错。
  await put(
    repo,
    `.ai/agents/${name}/${name}-leader.md`,
    `---\nname: ${name}-leader\ndescription: ${name} 蜂群 leader。\n---\n\n蜂群 ${name} 的四阶段契约调度。\n`,
  );
}

test('should_inject_swarm_trigger_three_modes_into_entry_stubs', async (t) => {
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  const cases = [
    ['auto', /5\. 蜂群流程开关（engine\.ai-coding-dev: auto）：蜂群 ai-coding-dev.*功能开发类任务自动/],
    ['always', /5\. 蜂群流程开关（engine\.ai-coding-dev: always）：凡会产生业务代码的开发任务一律按蜂群 ai-coding-dev/],
    ['never', /5\. 蜂群流程开关（engine\.ai-coding-dev: never）：蜂群 ai-coding-dev 的四阶段契约流程已停用/],
  ];
  for (const [mode, re] of cases) {
    await put(repo, CONFIG, `version: 1\nengine:\n  ai-coding-dev: ${mode}\n`);
    succeeds(run(repo));
    const agents = await readFile(join(repo, 'AGENTS.md'), 'utf8');
    assert.match(agents, re);
    assert.match(agents, /\.ai\/agents\/ai-coding-dev\/ai-coding-dev-leader\.md/);
    assert.match(agents, /\.ai-memory\/playbooks\/multi-agent\.md/);
    assert.match(agents, /\/req/);
    // @import 去重存根同样注入蜂群触发段（不占 RULES.md 预算）
    assert.match(await readFile(join(repo, 'CODEBUDDY.md'), 'utf8'), re);
    // Cursor project.mdc 为纯指针（ADR 2026-11-18-01），不重复入口存根的铁律节选正文
    assert.ok(!(await readFile(join(repo, '.cursor/rules/project.mdc'), 'utf8')).includes('铁律（节选'));
    succeeds(run(repo, ['--check']));
  }
});

test('should_inject_manual_swarm_trigger_into_entry_stubs_without_command_entry', async (t) => {
  // manual 档三处入口形态：根入口存根（AGENTS/CLAUDE/GEMINI）+ @import 去重存根（CODEBUDDY）。
  // manual 不注入四阶段流程 / leader 路径 / 手册 / /req 命令入口。
  const { repo } = await fixture(t);
  await putSwarm(repo, 'code-review');
  await put(repo, CONFIG, 'engine:\n  code-review: manual\n');
  succeeds(run(repo));
  const manual =
    /5\. 蜂群流程开关（engine\.code-review: manual）：蜂群 code-review 仅在用户对话显式要求时调用，无自动触发、无命令入口；产物与流程口径见该蜂群说明文件。/;
  for (const file of ['AGENTS.md', 'CLAUDE.md', 'GEMINI.md', 'CODEBUDDY.md']) {
    const text = await readFile(join(repo, file), 'utf8');
    assert.match(text, manual, `${file} 应注入 manual 档触发段`);
    assert.doesNotMatch(text, /contracts\/<REVIEW-ID>\.md/, `${file} manual 档不写评审专属产物语义`);
    assert.doesNotMatch(text, /\.ai\/agents\/code-review\/code-review-leader\.md/, `${file} manual 档不注入 leader 路径`);
    assert.doesNotMatch(text, /\/req/, `${file} manual 档不注入命令入口`);
    assert.doesNotMatch(text, /multi-agent\.md/, `${file} manual 档不注入四阶段手册`);
  }
  succeeds(run(repo, ['--check']));
});

test('should_number_ai_coding_dev_and_code_review_from_five_when_both_swarms_exist', async (t) => {
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  await putSwarm(repo, 'code-review');
  await put(repo, CONFIG, 'engine:\n  code-review: manual\n  ai-coding-dev: auto\n');
  succeeds(run(repo));
  const agents = await readFile(join(repo, 'AGENTS.md'), 'utf8');
  assert.match(agents, /5\. 蜂群流程开关（engine\.ai-coding-dev: auto）/);
  assert.match(agents, /6\. 蜂群流程开关（engine\.code-review: manual）：蜂群 code-review 仅在用户对话显式要求时调用/);
  assert.match(agents, /6\. 蜂群流程开关（engine\.code-review: manual）[^]*产物与流程口径见该蜂群说明文件。/);
  succeeds(run(repo, ['--check']));
});

test('should_still_reject_code_review_key_when_swarm_directory_absent', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, CONFIG, 'engine:\n  code-review: manual\n');
  // fixture 无 code-review 蜂群目录 → 即使 manual 是合法档位，未知键仍报错。
  fails(run(repo), /未知 engine 键「code-review」/);
  await putSwarm(repo, 'code-review');
  succeeds(run(repo));
  succeeds(run(repo, ['--check']));
});

test('should_number_swarm_triggers_from_five_for_each_swarm_key', async (t) => {
  const { repo } = await fixture(t);
  await putSwarm(repo, 'zeta');
  await putSwarm(repo, 'alpha');
  await put(repo, CONFIG, 'engine:\n  zeta: always\n  alpha: auto\n');
  succeeds(run(repo));
  const agents = await readFile(join(repo, 'AGENTS.md'), 'utf8');
  assert.match(agents, /5\. 蜂群流程开关（engine\.alpha: auto）/);
  assert.match(agents, /6\. 蜂群流程开关（engine\.zeta: always）/);
  assert.match(agents, /\.ai\/agents\/alpha\/alpha-leader\.md/);
  assert.match(agents, /\.ai\/agents\/zeta\/zeta-leader\.md/);
  succeeds(run(repo, ['--check']));
});

test('should_accept_swarm_key_only_when_swarm_directory_exists', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, CONFIG, 'engine:\n  ai-coding-dev: auto\n');
  // fixture 无蜂群目录 → 蜂群键视为未知键
  fails(run(repo), /未知 engine 键「ai-coding-dev」/);
  await putSwarm(repo, 'ai-coding-dev');
  succeeds(run(repo));
  succeeds(run(repo, ['--check']));
});

test('should_generalize_swarm_trigger_text_without_swarm_purpose', async (t) => {
  // 用途说明只登记在 AGENT-POOL.md：非 ai-coding-dev 蜂群的触发段不得硬编码「支撑 ai-coding 开发」。
  const { repo } = await fixture(t);
  await putSwarm(repo, 'alpha');
  for (const [mode, re] of [
    ['auto', /5\. 蜂群流程开关（engine\.alpha: auto）：蜂群 alpha 的功能开发类任务自动按其四阶段契约流程执行/],
    ['always', /5\. 蜂群流程开关（engine\.alpha: always）：凡会产生业务代码的开发任务一律按蜂群 alpha 的四阶段契约流程执行/],
  ]) {
    await put(repo, CONFIG, `engine:\n  alpha: ${mode}\n`);
    succeeds(run(repo));
    const agents = await readFile(join(repo, 'AGENTS.md'), 'utf8');
    assert.match(agents, re);
    assert.doesNotMatch(agents, /支撑 ai-coding 开发/);
    succeeds(run(repo, ['--check']));
  }
});

test('should_reject_flat_md_file_at_agents_root', async (t) => {
  // 布局不变量 1：.ai/agents/ 根级只允许蜂群目录与 AGENT-POOL.md（早于配置加载）。
  const { repo } = await fixture(t);
  await put(repo, '.ai/agents/AGENT-POOL.md', 'Source-only documentation\n');
  await put(repo, '.ai/agents/foo.md', '# Flat agent\n');
  const before = await snapshot(repo);
  for (const args of [[], ['--check']]) {
    fails(run(repo, args), /\.ai\/agents\/foo\.md：根级只允许蜂群目录与唯一说明文件 AGENT-POOL\.md/);
    assert.deepEqual(await snapshot(repo), before);
  }
});

test('should_reject_swarm_directory_without_leader', async (t) => {
  // 布局不变量 2：每个蜂群目录必须有 <蜂群名>-leader.md，保证触发段引用路径存在。
  const { repo } = await fixture(t);
  await mkdir(join(repo, '.ai/agents/alpha'), { recursive: true });
  await put(repo, CONFIG, 'engine:\n  alpha: auto\n');
  const before = await snapshot(repo);
  for (const args of [[], ['--check']]) {
    fails(run(repo, args), /蜂群 alpha 缺少 leader：\.ai\/agents\/alpha\/alpha-leader\.md/);
    assert.deepEqual(await snapshot(repo), before);
  }
});

test('should_reject_symlink_as_swarm_leader', async (t) => {
  const { repo, outside } = await fixture(t);
  await putSwarm(repo, 'alpha');
  const leader = join(repo, '.ai/agents/alpha/alpha-leader.md');
  await rm(leader);
  await put(outside, 'external-leader.md', '# External leader\n');
  if (!await makeLink(t, join(outside, 'external-leader.md'), leader)) return;
  const before = await snapshot(repo);
  for (const args of [[], ['--check']]) {
    fails(run(repo, args), /拒绝符号链接路径：\.ai\/agents\/alpha\/alpha-leader\.md/);
    assert.equal(await readFile(join(outside, 'external-leader.md'), 'utf8'), '# External leader\n');
    assert.deepEqual(await snapshot(repo), before);
  }
});

test('should_reject_directory_as_swarm_leader', async (t) => {
  const { repo } = await fixture(t);
  await putSwarm(repo, 'alpha');
  const leader = join(repo, '.ai/agents/alpha/alpha-leader.md');
  await rm(leader);
  await mkdir(leader, { recursive: true });
  const before = await snapshot(repo);
  for (const args of [[], ['--check']]) {
    fails(run(repo, args), /拒绝非普通文件目标（目录不会删除）：\.ai\/agents\/alpha\/alpha-leader\.md/);
    assert.deepEqual(await snapshot(repo), before);
  }
});

test('should_inject_no_swarm_trigger_when_swarm_key_absent', async (t) => {
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  succeeds(run(repo));
  let agents = await readFile(join(repo, 'AGENTS.md'), 'utf8');
  assert.doesNotMatch(agents, /蜂群流程开关/);
  assert.doesNotMatch(agents, /engine\.ai-coding-dev/);
  // 配置在位但无蜂群键（仅固定键）同样不注入
  await put(repo, CONFIG, 'version: 1\nengine:\n  rulesBudgetBytes: 8192\n');
  succeeds(run(repo));
  agents = await readFile(join(repo, 'AGENTS.md'), 'utf8');
  assert.doesNotMatch(agents, /蜂群流程开关/);
  succeeds(run(repo, ['--check']));
});

test('should_flatten_swarm_agent_sources_into_tool_dirs', async (t) => {
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  await put(
    repo,
    '.ai/agents/ai-coding-dev/helper.md',
    '---\nname: helper\ndescription: Helper agent.\ntools: [Read]\nmodel: inherit\n---\n\nNESTED-BODY\n',
  );
  succeeds(run(repo));
  // Claude 目标为原样拷贝，溯源尾注保留完整源 rel
  const claude = await readFile(join(repo, '.claude/agents/helper.md'), 'utf8');
  assert.match(claude, /派生自 \.ai\/agents\/ai-coding-dev\/helper\.md/);
  assert.ok(claude.includes('NESTED-BODY'));
  // 引用存根目标也扁平到 basename，指针指向完整源 rel
  for (const dir of ['.gemini/agents', '.qoder/agents', '.cursor/agents', '.trae/agents', '.codebuddy/agents']) {
    assert.match(await readFile(join(repo, dir, 'helper.md'), 'utf8'), /\.ai\/agents\/ai-coding-dev\/helper\.md/, dir);
  }
  // 蜂群目录名不落入工具目录
  for (const dir of ['.claude/agents', '.qoder/agents', '.cursor/agents', '.trae/agents', '.codebuddy/agents']) {
    await assert.rejects(lstat(join(repo, dir, 'ai-coding-dev')), { code: 'ENOENT' });
  }
  // 不同蜂群同名 agent 仍经 destinations 登记簿判为冲突（先补 leader，避免被布局校验掩盖）
  await putSwarm(repo, 'other-swarm');
  await put(repo, '.ai/agents/other-swarm/helper.md', '# duplicate\n');
  fails(run(repo), /派生目标冲突/);
  await rm(join(repo, '.ai/agents/other-swarm'), { recursive: true });
  succeeds(run(repo, ['--check']));
});

test('should_treat_missing_local_only_target_as_ok_in_check_but_mismatch_as_fail', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, '.ai/tools/zcode/ZCODE.md', '# ZCode dir\n');
  // ZCode 存根不再有手写源：用蜂群代理源生成 .zcode/agents/helper.md，再对其漂移。
  await putSwarm(repo, 'ai-coding-dev');
  await put(repo, '.ai/agents/ai-coding-dev/helper.md', '---\nname: helper\ndescription: Helper agent.\ntools: [Read]\nmodel: inherit\n---\n\nHELPER-BODY\n');
  succeeds(run(repo)); // sync：写本机 .zcode
  succeeds(run(repo, ['--check']));
  await rm(join(repo, '.zcode'), { recursive: true }); // 模拟全新检出/CI：本机目标缺失
  const ok = run(repo, ['--check']);
  succeeds(ok);
  assert.match(ok.stdout, /跳过校验/);
  succeeds(run(repo)); // sync 照常重建本机目标
  succeeds(run(repo, ['--check']));
  // 存在但内容不一致 → 仍判错（localOnly 只豁免缺失，不豁免漂移）
  await put(repo, '.zcode/agents/helper.md', '# drifted\n');
  fails(run(repo, ['--check']), /与 \.ai\/ 不同步/);
});

test('should_derive_zcode_agent_and_command_stubs_from_public_pools', async (t) => {
  // P1：ZCode 存根改为公共池派生（消除手写镜像漏配）。frontmatter 原样保留供注册，正文仅指针。
  const { repo } = await fixture(t);
  await put(repo, '.ai/tools/zcode/ZCODE.md', '# ZCode dir\n');
  await putSwarm(repo, 'ai-coding-dev');
  const agentSource = '---\nname: helper\ndescription: Helper agent for zcode.\ntools: [Read]\nmodel: inherit\n---\n\nSECRET-AGENT-BODY\n';
  await put(repo, '.ai/agents/ai-coding-dev/helper.md', agentSource);
  await put(repo, '.ai/commands/ping.md', '---\ndescription: Ping command.\n---\n\nSECRET-COMMAND-BODY\n');
  succeeds(run(repo));

  const descriptionOf = (text) => text.match(/^---\n[\s\S]*?\n---/)[0].split('\n').find((line) => line.startsWith('description:'));
  const agent = await readFile(join(repo, '.zcode/agents/helper.md'), 'utf8');
  assert.equal(descriptionOf(agent), descriptionOf(agentSource), '.zcode agent frontmatter description 应与源逐字一致');
  assert.match(agent, /\.ai\/agents\/ai-coding-dev\/helper\.md/);
  assert.match(agent, /被 spawn 后第一步：立即完整读取上述文件并严格遵循/);
  assert.ok(!agent.includes('SECRET-AGENT-BODY'), '存根不得包含源正文');

  const command = await readFile(join(repo, '.zcode/commands/ping.md'), 'utf8');
  assert.match(command, /description: Ping command\./);
  assert.match(command, /\.ai\/commands\/ping\.md/);
  assert.match(command, /\$ARGUMENTS/);
  assert.ok(!command.includes('SECRET-COMMAND-BODY'), '存根不得包含源正文');
  succeeds(run(repo, ['--check']));
});

test('should_derive_codebuddy_entries_as_official_aligned_references', async (t) => {
  // CodeBuddy 官方对齐（ADR 2026-11-17-03，取代 2026-11-17-01 的全量镜像）：入口 CODEBUDDY.md 用官方
  // `@` 导入引用（不重复铁律节选）；公共池 agents/skills/commands 派生为引用存根（frontmatter 保留供
  // 注册，正文仅指针）；tools 专属目录说明照常前置「生成产物」标头；localOnly 三段语义不变。
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  await put(repo, CONFIG, 'engine:\n  ai-coding-dev: auto\n');
  await put(repo, '.ai/agents/ai-coding-dev/helper.md', '---\nname: helper\ndescription: Helper agent.\ntools: [Read]\n---\n\nSECRET-BODY-AGENT\n');
  await put(repo, '.ai/skills/review/SKILL.md', '---\nname: review\ndescription: Review skill.\n---\n\nSECRET-BODY-SKILL\n');
  await put(repo, '.ai/commands/review.md', '---\ndescription: Review command.\n---\n\nSECRET-BODY-COMMAND\n');
  await put(repo, '.ai/commands/plain.md', '# Plain command\n');
  await put(repo, '.ai/tools/codebuddy/CODEBUDDY-CLI.md', '# CodeBuddy dir\n');
  succeeds(run(repo));
  const entry = await readFile(join(repo, 'CODEBUDDY.md'), 'utf8');
  assert.match(entry, /@\.ai\/RULES\.md/);
  assert.ok(!entry.includes('铁律（节选'), '入口用 @ 导入内联全文，不得重复铁律节选');
  assert.match(entry, /蜂群流程开关/, '引擎注入的蜂群流程开关不属于 RULES.md，应保留');
  const agent = await readFile(join(repo, '.codebuddy/agents/helper.md'), 'utf8');
  assert.match(agent, /tools: \[Read\]/);
  assert.match(agent, /\.ai\/agents\/ai-coding-dev\/helper\.md/);
  assert.ok(!agent.includes('SECRET-BODY-AGENT'));
  const skill = await readFile(join(repo, '.codebuddy/skills/review/SKILL.md'), 'utf8');
  assert.match(skill, /description: Review skill\./);
  assert.match(skill, /\.ai\/skills\/review\/SKILL\.md/);
  assert.ok(!skill.includes('SECRET-BODY-SKILL'));
  const command = await readFile(join(repo, '.codebuddy/commands/review.md'), 'utf8');
  assert.match(command, /description: Review command\./);
  assert.match(command, /\.ai\/commands\/review\.md/);
  assert.ok(!command.includes('SECRET-BODY-COMMAND'));
  // 无 frontmatter 的池文件：合成最小注册头（name + description），仍不含正文
  const plain = await readFile(join(repo, '.codebuddy/commands/plain.md'), 'utf8');
  assert.match(plain, /^---\nname: plain\ndescription: /);
  assert.ok(!plain.includes('# Plain command'));
  // 派生标记在位（孤儿检测依赖）+ 目录说明标头 + --check 幂等
  assert.match(agent, /派生自 \.ai\/agents\/ai-coding-dev\/helper\.md/);
  assert.match(await readFile(join(repo, '.codebuddy/CODEBUDDY-CLI.md'), 'utf8'), /生成产物/);
  succeeds(run(repo, ['--check']));
  // localOnly 三段语义：缺失跳过 → 重建 → 漂移判错
  await rm(join(repo, '.codebuddy'), { recursive: true });
  const ok = run(repo, ['--check']);
  succeeds(ok);
  assert.match(ok.stdout, /跳过校验/);
  succeeds(run(repo));
  await put(repo, '.codebuddy/agents/helper.md', '# drifted\n');
  fails(run(repo, ['--check']), /与 \.ai\/ 不同步/);
});

test('should_derive_cursor_entries_as_official_aligned_references', async (t) => {
  // Cursor 官方对齐（ADR 2026-11-18-01）：rules 纯指针（不重复入口存根铁律/多代理开关），
  // 三池引用存根（frontmatter 原样保留供注册，正文仅指针，磁盘零重复）。
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  await put(repo, '.ai/agents/ai-coding-dev/helper.md', '---\nname: helper\ndescription: Helper agent.\ntools: [Read]\nmodel: inherit\n---\n\nSECRET-BODY-AGENT\n');
  await put(repo, '.ai/skills/review/SKILL.md', '---\nname: review\ndescription: Review skill.\n---\n\nSECRET-BODY-SKILL\n');
  await put(repo, '.ai/commands/review.md', '---\ndescription: Review command.\n---\n\nSECRET-BODY-COMMAND\n');
  await put(repo, '.ai/commands/plain.md', '# Plain command\n');
  succeeds(run(repo));
  const rule = await readFile(join(repo, '.cursor/rules/project.mdc'), 'utf8');
  assert.match(rule, /alwaysApply: true/);
  assert.match(rule, /\.ai\/RULES\.md/);
  assert.ok(!rule.includes('铁律（节选'), 'rules 存根不得重复入口存根的铁律节选');
  const agent = await readFile(join(repo, '.cursor/agents/helper.md'), 'utf8');
  assert.match(agent, /tools: \[Read\]/);
  assert.match(agent, /model: inherit/);
  assert.match(agent, /\.ai\/agents\/ai-coding-dev\/helper\.md/);
  assert.ok(!agent.includes('SECRET-BODY-AGENT'));
  const skill = await readFile(join(repo, '.cursor/skills/review/SKILL.md'), 'utf8');
  assert.match(skill, /description: Review skill\./);
  assert.match(skill, /\.ai\/skills\/review\/SKILL\.md/);
  assert.ok(!skill.includes('SECRET-BODY-SKILL'));
  const command = await readFile(join(repo, '.cursor/commands/review.md'), 'utf8');
  assert.match(command, /description: Review command\./);
  assert.match(command, /\.ai\/commands\/review\.md/);
  assert.ok(!command.includes('SECRET-BODY-COMMAND'));
  const plain = await readFile(join(repo, '.cursor/commands/plain.md'), 'utf8');
  assert.match(plain, /^---\nname: plain\ndescription: /);
  assert.ok(!plain.includes('# Plain command'));
  assert.match(agent, /派生自 \.ai\/agents\/ai-coding-dev\/helper\.md/);
  succeeds(run(repo, ['--check']));
});

test('should_derive_qoder_entries_as_non_duplicating_references', async (t) => {
  // Qoder 官方对齐（ADR 2026-11-17-02）：rules 纯指针（不重复入口存根铁律），
  // 三池引用存根（frontmatter 原样保留供注册，正文仅指针，磁盘零重复）。
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  await put(repo, '.ai/agents/ai-coding-dev/helper.md', '---\nname: helper\ndescription: Helper agent.\ntools: [Read]\n---\n\nSECRET-BODY-AGENT\n');
  await put(repo, '.ai/skills/review/SKILL.md', '---\nname: review\ndescription: Review skill.\n---\n\nSECRET-BODY-SKILL\n');
  await put(repo, '.ai/commands/review.md', '---\ndescription: Review command.\n---\n\nSECRET-BODY-COMMAND\n');
  await put(repo, '.ai/commands/plain.md', '# Plain command\n');
  succeeds(run(repo));
  const rule = await readFile(join(repo, '.qoder/rules/project_rules.md'), 'utf8');
  assert.match(rule, /alwaysApply: true/);
  assert.match(rule, /\.ai\/RULES\.md/);
  assert.ok(!rule.includes('铁律（节选'), 'rules 存根不得重复入口存根的铁律节选');
  const agent = await readFile(join(repo, '.qoder/agents/helper.md'), 'utf8');
  assert.match(agent, /tools: \[Read\]/);
  assert.match(agent, /\.ai\/agents\/ai-coding-dev\/helper\.md/);
  assert.ok(!agent.includes('SECRET-BODY-AGENT'));
  const skill = await readFile(join(repo, '.qoder/skills/review/SKILL.md'), 'utf8');
  assert.match(skill, /description: Review skill\./);
  assert.match(skill, /\.ai\/skills\/review\/SKILL\.md/);
  assert.ok(!skill.includes('SECRET-BODY-SKILL'));
  const command = await readFile(join(repo, '.qoder/commands/review.md'), 'utf8');
  assert.match(command, /description: Review command\./);
  assert.match(command, /\.ai\/commands\/review\.md/);
  assert.ok(!command.includes('SECRET-BODY-COMMAND'));
  // 无 frontmatter 的池文件：合成最小注册头（name + description），仍不含正文
  const plain = await readFile(join(repo, '.qoder/commands/plain.md'), 'utf8');
  assert.match(plain, /^---\nname: plain\ndescription: /);
  assert.ok(!plain.includes('# Plain command'));
  // 派生标记在位（孤儿检测依赖）且 --check 通过
  assert.match(agent, /派生自/);
  succeeds(run(repo, ['--check']));
});

test('should_derive_trae_entries_as_official_aligned_references', async (t) => {
  // Trae 官方对齐（ADR 2026-11-17-04）：rules 纯指针 + 官方 frontmatter（alwaysApply/description，
  // 不重复铁律节选）；三池引用存根（frontmatter 保留供注册，正文仅指针）；agents frontmatter 适配
  // （tools 数组→逗号分隔字符串、移除 model: inherit）。
  const { repo } = await fixture(t);
  await putSwarm(repo, 'ai-coding-dev');
  await put(repo, '.ai/agents/ai-coding-dev/helper.md', '---\nname: helper\ndescription: Helper agent.\ntools: [Read, Grep]\nmodel: inherit\n---\n\nSECRET-BODY-AGENT\n');
  await put(repo, '.ai/skills/review/SKILL.md', '---\nname: review\ndescription: Review skill.\n---\n\nSECRET-BODY-SKILL\n');
  await put(repo, '.ai/commands/review.md', '---\ndescription: Review command.\n---\n\nSECRET-BODY-COMMAND\n');
  await put(repo, '.ai/commands/plain.md', '# Plain command\n');
  succeeds(run(repo));
  // rules：官方 frontmatter + 纯指针，不重复铁律节选
  const rule = await readFile(join(repo, '.trae/rules/project_rules.md'), 'utf8');
  assert.match(rule, /^---\ndescription: .*\nalwaysApply: true\n---/);
  assert.match(rule, /\.ai\/RULES\.md/);
  assert.ok(!rule.includes('铁律（节选'), 'rules 存根不得重复入口存根的铁律节选');
  // agents：frontmatter 适配（tools 字符串、无 model: inherit）+ 正文零重复
  const agent = await readFile(join(repo, '.trae/agents/helper.md'), 'utf8');
  assert.match(agent, /tools: Read, Grep/);
  assert.ok(!agent.includes('model: inherit'), 'Trae 不支持 model: inherit，应移除');
  assert.match(agent, /\.ai\/agents\/ai-coding-dev\/helper\.md/);
  assert.ok(!agent.includes('SECRET-BODY-AGENT'));
  // skills：frontmatter 原样 + 正文零重复
  const skill = await readFile(join(repo, '.trae/skills/review/SKILL.md'), 'utf8');
  assert.match(skill, /description: Review skill\./);
  assert.match(skill, /\.ai\/skills\/review\/SKILL\.md/);
  assert.ok(!skill.includes('SECRET-BODY-SKILL'));
  // commands：frontmatter 原样 + 正文零重复
  const command = await readFile(join(repo, '.trae/commands/review.md'), 'utf8');
  assert.match(command, /description: Review command\./);
  assert.match(command, /\.ai\/commands\/review\.md/);
  assert.ok(!command.includes('SECRET-BODY-COMMAND'));
  // 无 frontmatter 的池文件：合成最小注册头，仍不含正文
  const plain = await readFile(join(repo, '.trae/commands/plain.md'), 'utf8');
  assert.match(plain, /^---\nname: plain\ndescription: /);
  assert.ok(!plain.includes('# Plain command'));
  // 派生标记在位（孤儿检测依赖）+ --check 幂等
  assert.match(agent, /派生自 \.ai\/agents\/ai-coding-dev\/helper\.md/);
  succeeds(run(repo, ['--check']));
});

test('should_fail_when_ci_inline_ai_paths_drift_from_single_list', async (t) => {
  // AI 路径单一清单（ADR 2026-11-17-01）：ci.yml 的 AI_PATHS 内联副本与 .ai/hooks/ai-paths.list
  // 不一致时，sync 与 --check 都必须失败（未写入任何产物）。
  const { repo } = await fixture(t);
  await put(repo, '.ai/hooks/ai-paths.list', '# AI paths\n.ai\n.ai-memory\n:(glob)**/AGENTS.md\n');
  const ci = [
    'name: ci',
    'on: push',
    'jobs:',
    '  mirror-sync:',
    '    steps:',
    '      - run: |',
    '          AI_PATHS=(',
    '            .ai .ai-memory',
    '            :(glob)**/AGENTS.md',
    '          )',
    '      - run: git diff --exit-code origin/a origin/b -- "${AI_PATHS[@]}"',
  ].join('\n');
  await put(repo, '.ai/ci/ci.yml', ci);
  succeeds(run(repo));
  succeeds(run(repo, ['--check']));
  await put(repo, '.ai/ci/ci.yml', ci.replace('.ai .ai-memory', '.ai'));
  const before = await snapshot(repo);
  fails(run(repo), /AI_PATHS 内联清单与 \.ai\/hooks\/ai-paths\.list 不一致/);
  fails(run(repo, ['--check']), /AI_PATHS 内联清单与 \.ai\/hooks\/ai-paths\.list 不一致/);
  assert.deepEqual(await snapshot(repo), before);
});

test('should_fail_when_only_one_of_ai_paths_list_or_ci_present', async (t) => {
  // 清单与 ci.yml 是结构配套：真实仓库恒同时在位；最小 fixture 两者皆缺则跳过校验（向后兼容），
  // 单缺任一即报错，避免半套结构静默漏检。
  const { repo } = await fixture(t);
  succeeds(run(repo)); // 两者皆缺（最小 fixture）：跳过
  await put(repo, '.ai/hooks/ai-paths.list', '.ai\n');
  fails(run(repo), /缺少 \.ai\/ci\/ci\.yml/);
  await rm(join(repo, '.ai/hooks/ai-paths.list'));
  await put(repo, '.ai/ci/ci.yml', 'name: ci\n');
  fails(run(repo), /缺少 \.ai\/hooks\/ai-paths\.list/);
});

test('should_accept_environment_file_pointer_when_valid_yaml_name', async (t) => {
  const { repo } = await fixture(t);
  for (const yaml of [
    'environment:\n  file: local.yaml\n',
    'environment:\n  file: cloud-dev.yaml\n',
    'version: 1\nengine:\n  entryMode: stub\nenvironment:\n  file: cloud-test.yaml # inline\n',
  ]) {
    await put(repo, CONFIG, yaml);
    succeeds(run(repo));
    succeeds(run(repo, ['--check']));
  }
});

test('should_verify_environment_file_exists_when_env_dir_present', async (t) => {
  // 存在性折中校验（审查 2026-11-14）：.ai/env/ 目录在位时，指针指向不存在的文件必须在写入前报错；
  // 目录缺失（fixture 最小仓库）维持原兼容行为。
  const { repo } = await fixture(t);
  await mkdir(join(repo, '.ai/env'), { recursive: true });
  await put(repo, '.ai/env/local.yaml', 'env: local\n');
  await put(repo, CONFIG, 'environment:\n  file: local.yaml\n');
  succeeds(run(repo));
  succeeds(run(repo, ['--check']));
  await put(repo, CONFIG, 'environment:\n  file: cloud-dev.yaml\n');
  const before = await snapshot(repo);
  fails(run(repo), /不在 \.ai\/env\/ 下/);
  fails(run(repo, ['--check']), /不在 \.ai\/env\/ 下/);
  assert.deepEqual(await snapshot(repo), before);
});

const badConfigs = [
  ['unknown_top_key', 'foo: 1\n', /未知顶层键/],
  ['unknown_engine_key', 'engine:\n  bogus: true\n', /未知 engine 键/],
  ['bad_entryMode', 'engine:\n  entryMode: nope\n', /entryMode 必须为/],
  ['bad_orphan_type', 'engine:\n  orphanDetection: yes\n', /orphanDetection 必须为布尔/],
  ['bad_budget_negative', 'engine:\n  rulesBudgetBytes: -5\n', /rulesBudgetBytes 必须为正整数/],
  ['bad_budget_float', 'engine:\n  rulesBudgetBytes: 1.5\n', /rulesBudgetBytes 必须为正整数/],
  ['bad_swarm_mode', 'engine:\n  ai-coding-dev: maybe\n', /必须为 auto\|always\|never\|manual/, ['ai-coding-dev']],
  ['unknown_swarm_key_without_directory', 'engine:\n  ai-coding-dev: auto\n', /未知 engine 键/, []],
  ['bad_version', 'version: 2\n', /version 必须为 1/],
  ['array_unsupported', 'engine:\n  - a\n', /不支持数组/],
  ['tab_indent', 'engine:\n\tentryMode: stub\n', /不支持 Tab/],
  ['engine_not_map', 'engine: stub\n', /engine 必须是映射/],
  ['unknown_environment_key', 'environment:\n  bogus: true\n', /未知 environment 键/],
  ['legacy_localDebug_key', 'environment:\n  localDebug: cloud\n', /未知 environment 键/],
  ['bad_file_type', 'environment:\n  file: 123\n', /file 必须为/],
  ['bad_file_suffix', 'environment:\n  file: dev.yml\n', /file 必须为/],
  ['file_with_path', 'environment:\n  file: env/dev.yaml\n', /file 必须为/],
  ['environment_not_map', 'environment: cloud-dev.yaml\n', /environment 必须是映射/],
];
for (const [name, yaml, msg, swarms = []] of badConfigs) {
  test(`should_reject_invalid_config_before_writing_when_${name}`, async (t) => {
    const { repo } = await fixture(t);
    for (const swarm of swarms) await putSwarm(repo, swarm);
    await put(repo, CONFIG, yaml);
    const before = await snapshot(repo);
    for (const args of [[], ['--check']]) {
      fails(run(repo, args), msg);
      assert.deepEqual(await snapshot(repo), before);
    }
  });
}
