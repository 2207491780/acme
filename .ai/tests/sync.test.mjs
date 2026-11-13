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
  await put(repo, '.ai/agents/helper.md', '# Helper\n');
  await put(repo, '.ai/agents/AGENT-POOL.md', 'Source-only documentation\n');
  await put(repo, '.ai/skills/review/SKILL.md', '# Review\n');
  await put(repo, '.ai/commands/review.md', '# Review command\n');
  await put(repo, '.ai/tools/claude/CLAUDE-CODE.md', '# Claude directory\n');
  succeeds(run(repo));
  const combined = await readFile(join(repo, '.ai/RULES.md'), 'utf8');
  assert.ok(combined.endsWith(rule.trim() + '\n\n# Nested\n\nSecond rule.\n'));
  assert.match(await readFile(join(repo, 'CLAUDE.md'), 'utf8'), /@\.ai\/RULES\.md/);
  assert.match(await readFile(join(repo, 'project/CLAUDE.md'), 'utf8'), /^@AGENTS\.md\n/);
  assert.match(await readFile(join(repo, '.claude/agents/helper.md'), 'utf8'), /派生自 \.ai\/agents\/helper\.md/);
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
    sources: ['.ai/agents/helper.md', '.ai/tools/claude/agents/helper.md'],
    target: '.claude/agents/helper.md',
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
    sources: ['.ai/agents/helper.md', '.ai/tools/claude/agents/HELPER.md'],
    target: '.claude/agents/HELPER.md',
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
  await put(repo, '.claude/agents/stale.md', '# Stale\n\n<!-- 派生自 .ai/agents/gone.md -->\n');
  fails(run(repo, ['--check']), /孤儿派生文件/);
  await put(repo, CONFIG, 'version: 1\nengine:\n  orphanDetection: false\n');
  const ok = run(repo, ['--check']);
  succeeds(ok);
  assert.match(ok.stdout, /孤儿检测已关闭/);
});

test('should_enforce_custom_rulesBudgetBytes_from_config', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, CONFIG, 'version: 1\nengine:\n  rulesBudgetBytes: 200\n');
  const before = await snapshot(repo);
  fails(run(repo), /bytes > 200 bytes/);
  assert.deepEqual(await snapshot(repo), before);
});

test('should_inject_team_leader_trigger_per_mode_into_entry_stubs', async (t) => {
  const { repo } = await fixture(t);
  const cases = [
    ['engine:\n  teamLeader: auto\n', /teamLeader: auto）：功能开发类任务应按 team-leader/],
    ['engine:\n  teamLeader: always\n', /teamLeader: always）：凡会产生业务代码的开发任务，一律按 team-leader/],
    ['engine:\n  teamLeader: never\n', /teamLeader: never）：team-leader 四阶段契约流程已停用/],
  ];
  for (const [yaml, re] of cases) {
    await put(repo, CONFIG, yaml);
    succeeds(run(repo));
    assert.match(await readFile(join(repo, 'AGENTS.md'), 'utf8'), re);
    assert.match(await readFile(join(repo, '.cursor/rules/project.mdc'), 'utf8'), re);
    succeeds(run(repo, ['--check']));
  }
  // 缺省（配置缺失）= never：入口存根明示流程停用，向后兼容既有行为
  await rm(join(repo, CONFIG));
  succeeds(run(repo));
  assert.match(await readFile(join(repo, 'AGENTS.md'), 'utf8'), /team-leader 四阶段契约流程已停用/);
  succeeds(run(repo, ['--check']));
});

test('should_treat_missing_local_only_target_as_ok_in_check_but_mismatch_as_fail', async (t) => {
  const { repo } = await fixture(t);
  await put(repo, '.ai/tools/zcode/ZCODE.md', '# ZCode dir\n');
  await put(repo, '.ai/tools/zcode/agents/helper.md', '# Helper stub\n');
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

const badConfigs = [
  ['unknown_top_key', 'foo: 1\n', /未知顶层键/],
  ['unknown_engine_key', 'engine:\n  bogus: true\n', /未知 engine 键/],
  ['bad_entryMode', 'engine:\n  entryMode: nope\n', /entryMode 必须为/],
  ['bad_orphan_type', 'engine:\n  orphanDetection: yes\n', /orphanDetection 必须为布尔/],
  ['bad_budget_negative', 'engine:\n  rulesBudgetBytes: -5\n', /rulesBudgetBytes 必须为正整数/],
  ['bad_budget_float', 'engine:\n  rulesBudgetBytes: 1.5\n', /rulesBudgetBytes 必须为正整数/],
  ['bad_team_leader', 'engine:\n  teamLeader: maybe\n', /teamLeader 必须为/],
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
for (const [name, yaml, msg] of badConfigs) {
  test(`should_reject_invalid_config_before_writing_when_${name}`, async (t) => {
    const { repo } = await fixture(t);
    await put(repo, CONFIG, yaml);
    const before = await snapshot(repo);
    for (const args of [[], ['--check']]) {
      fails(run(repo, args), msg);
      assert.deepEqual(await snapshot(repo), before);
    }
  });
}
