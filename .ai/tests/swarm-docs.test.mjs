import assert from 'node:assert/strict';
import { lstat, readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// 蜂群文档口径回归：上一轮审计发现三处漂移——工人角色仍称 Team Leader、leader description 用 ≠never、
// 第 0 步自检漏了「未配置（缺省）」停用分支。本文件只读真实仓库源文件，不建 fixture、不写任何文件
// （与 .ai/tests/*.test.mjs 风格一致）。ZCode 存根逐字校验已改为 sync.test.mjs 的 fixture 驱动用例。

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (rel) => readFile(join(root, rel), 'utf8');

const BACKEND = '.ai/agents/ai-coding-dev/backend-dev.md';
const FRONTEND = '.ai/agents/ai-coding-dev/frontend-dev.md';
const LEADER = '.ai/agents/ai-coding-dev/ai-coding-dev-leader.md';
const SKILL = '.ai/skills/team-leader/SKILL.md';

function bodyOf(text, file) {
  const withoutFrontmatter = text.replace(/^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/, '');
  assert.notEqual(withoutFrontmatter, text, `${file} 应有 frontmatter 以便剥离后校验正文`);
  return withoutFrontmatter;
}

function descriptionOf(text, file) {
  const fm = text.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/);
  assert.ok(fm, `${file} 应有 frontmatter`);
  const line = fm[1].match(/^description:\s*(.*)$/m);
  assert.ok(line, `${file} 的 frontmatter 应有 description 行`);
  return line[1].trim();
}

function step0Of(text, file) {
  const start = text.indexOf('第 0 步');
  assert.notEqual(start, -1, `${file} 应含第 0 步自检`);
  const rest = text.slice(start);
  const next = rest.indexOf('\n## ');
  assert.notEqual(next, -1, `${file} 第 0 步后应有下一节标题`);
  return rest.slice(0, next);
}

test('should_use_swarm_leader_wording_in_worker_role_body_when_referring_to_delegation', async () => {
  for (const rel of [BACKEND, FRONTEND]) {
    const body = bodyOf(await read(rel), rel);
    assert.doesNotMatch(body, /Team Leader/, `${rel} 正文不得残留旧称 Team Leader（应统一为「蜂群 leader」）`);
    assert.match(body, /只对蜂群 leader 的委派单负责/, `${rel} 正文应使用「只对蜂群 leader 的委派单负责」口径`);
  }
});

test('should_describe_leader_trigger_modes_in_source_role_file', async () => {
  // ZCode 存根 frontmatter 由 sync.mjs 从源逐字保留（见 sync.test.mjs 的派生用例），此处只校验源角色口径。
  const description = descriptionOf(await read(LEADER), LEADER);
  assert.match(description, /auto\|always/, `${LEADER} 的 description 应含 auto|always 档位`);
  assert.match(description, /never 或缺省时停用/, `${LEADER} 的 description 应含「never 或缺省时停用」口径`);
  assert.doesNotMatch(description, /≠never/, `${LEADER} 的 description 不得再用 ≠never 旧口径`);
});

test('should_treat_missing_swarm_key_as_disabled_in_both_step_zero_checks', async () => {
  const lines = [];
  for (const rel of [SKILL, LEADER]) {
    const section = step0Of(await read(rel), rel);
    assert.match(section, /未配置（缺省）/, `${rel} 第 0 步应含「未配置（缺省）」分支`);
    assert.match(section, /视为停用，停止本流程并按常规方式处理/, `${rel} 第 0 步缺省分支应停用并按常规方式处理`);
    lines.push(section.split('\n').find((line) => line.includes('缺省')));
  }
  assert.equal(lines[0], lines[1], `${SKILL} 与 ${LEADER} 第 0 步缺省分支措辞应一致`);
});

test('should_reference_same_engine_key_in_both_step_zero_checks_when_swarm_is_ai_coding_dev', async () => {
  const keysOf = (section) => section.match(/`engine\.[^`]+`/g) ?? [];
  const skillKeys = keysOf(step0Of(await read(SKILL), SKILL));
  const leaderKeys = keysOf(step0Of(await read(LEADER), LEADER));
  assert.deepEqual(skillKeys, leaderKeys, `${SKILL} 与 ${LEADER} 第 0 步的 engine 键引用应一致`);
  assert.ok(skillKeys.includes('`engine.ai-coding-dev`'), `${SKILL} 第 0 步应引用 \`engine.ai-coding-dev\``);
  assert.ok(![...skillKeys, ...leaderKeys].some((key) => key.includes('teamLeader')), '第 0 步不得再引用旧键 engine.teamLeader');
});

test('should_keep_only_swarm_directories_under_agents_when_ignoring_pool_doc', async () => {
  const entries = (await readdir(join(root, '.ai/agents'), { withFileTypes: true }))
    .filter((entry) => entry.name !== 'AGENT-POOL.md');
  assert.ok(entries.length > 0, '.ai/agents/ 下应至少有一个蜂群目录');
  for (const entry of entries) {
    assert.ok(entry.isDirectory(), `.ai/agents/ 下除 AGENT-POOL.md 外只允许蜂群目录，发现非目录项 ${entry.name}`);
    const rel = `.ai/agents/${entry.name}/${entry.name}-leader.md`;
    const stat = await lstat(join(root, rel));
    assert.ok(stat.isFile(), `蜂群目录 ${entry.name} 缺少 leader：${rel}`);
  }
});

// ---- code-review 蜂群文档口径：双蜂群并存 / leader 唯一写入口 / 工人临时脚本边界（同样只读真实源） ----
const CR_DIR = '.ai/agents/code-review';
const CR_ROLES = [
  'code-review-leader',
  'contract-preflight',
  'module-inspector-be',
  'module-inspector-fe',
  'quality-reviewer',
  'flow-simulator',
  'cross-contract-verifier',
  'impact-oracle',
  'verdict-arbitrator',
];
const CR_WORKERS = CR_ROLES.filter((role) => role !== 'code-review-leader' && role !== 'verdict-arbitrator');
const crPath = (role) => `${CR_DIR}/${role}.md`;

function toolsOf(text, file) {
  const fm = text.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/);
  assert.ok(fm, `${file} 应有 frontmatter`);
  const line = fm[1].match(/^tools:\s*\[([^\]]*)\]\s*$/m);
  assert.ok(line, `${file} 的 frontmatter 应有 tools 数组`);
  return line[1].split(',').map((tool) => tool.trim()).filter(Boolean);
}

test('should_derive_zcode_stubs_from_pools_without_handwritten_sources', async () => {
  // ZCode 存根不再有手写镜像：源目录必须消失，派生规则必须登记在 sync.mjs 的 zcode 工具项。
  for (const dir of ['agents', 'commands']) {
    await assert.rejects(lstat(join(root, '.ai/tools/zcode', dir)), { code: 'ENOENT' }, `.ai/tools/zcode/${dir}/ 不应再有手写源目录`);
  }
  const sync = await read('.ai/sync.mjs');
  assert.match(
    sync,
    /key: 'zcode',[\s\S]*?mirrorPools: \[[\s\S]*?\{ src: 'agents', transform: makeZcodeAgentStub, flat: true \},[\s\S]*?\{ src: 'commands', transform: makeZcodeCommandStub \}/,
    'sync.mjs 的 zcode 工具项应含 agents/commands 的 mirrorPools'
  );
});

test('should_keep_code_review_role_descriptions_and_names_consistent', async () => {
  const entries = (await readdir(join(root, CR_DIR), { withFileTypes: true })).map((entry) => entry.name).sort();
  assert.deepEqual(entries, CR_ROLES.map((role) => `${role}.md`).sort(), `${CR_DIR}/ 应恰好含 9 个角色文件`);
  for (const role of CR_ROLES) {
    const rel = crPath(role);
    const text = await read(rel);
    const fm = text.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/);
    assert.ok(fm, `${rel} 应有 frontmatter`);
    assert.match(fm[1], new RegExp(`^name:\\s*${role}\\s*$`, 'm'), `${rel} name 应与文件名一致`);
    const description = descriptionOf(text, rel);
    assert.match(description, /code-review 蜂群/, `${rel} description 应声明 code-review 蜂群`);
    assert.match(description, /engine\.code-review: manual/, `${rel} description 应声明 manual 档`);
    assert.ok(toolsOf(text, rel).length > 0, `${rel} tools 不应为空`);
    assert.match(bodyOf(text, rel), /回传|唯一写入口/, `${rel} 正文应声明交付给 leader`);
  }
});

test('should_keep_leader_as_only_write_entry_and_arbitrator_read_only', async () => {
  const leaderRel = crPath('code-review-leader');
  const leader = await read(leaderRel);
  const leaderTools = toolsOf(leader, leaderRel);
  for (const tool of ['Write', 'Edit', 'Bash', 'Read', 'Grep', 'Glob']) {
    assert.ok(leaderTools.includes(tool), `leader 应含 ${tool}`);
  }
  const leaderBody = bodyOf(leader, leaderRel);
  assert.match(leaderBody, /唯一写入口/, 'leader 应声明唯一写入口');
  assert.match(leaderBody, /contracts\/<REVIEW-ID>\.md/, 'leader 应声明唯一产物');
  assert.match(leaderBody, /第 7 节追加/, '多轮打回应在同一文件第 7 节追加');
  assert.match(leaderBody, /Bash \*\*仅用于 `node <script>\.mjs`/, 'leader 的 Bash 仅限 node 脚本');

  const arbRel = crPath('verdict-arbitrator');
  assert.deepEqual(toolsOf(await read(arbRel), arbRel), ['Read', 'Grep', 'Glob'], '裁决员必须纯只读');
  assert.match(bodyOf(await read(arbRel), arbRel), /不落盘/, '裁决员应声明不落盘');
});

test('should_limit_worker_script_writes_to_ai_tmp_only', async () => {
  for (const role of CR_WORKERS) {
    const rel = crPath(role);
    const text = await read(rel);
    const tools = toolsOf(text, rel);
    assert.ok(tools.includes('Write') && tools.includes('Bash'), `${rel} 工人角色应可写临时脚本（Write/Bash）`);
    assert.ok(!tools.includes('Edit'), `${rel} 工人角色不应有 Edit`);
    const body = bodyOf(text, rel);
    assert.match(body, /\.ai-tmp\/code-review\/<REVIEW-ID>\//, `${rel} 应限定临时脚本目录`);
    assert.match(body, /不(?:得)?写仓库内任何文件/, `${rel} 应禁写仓库内文件`);
    assert.match(body, /不(?:得)?写第二份 md/, `${rel} 应禁写第二份 md`);
    assert.match(body, /不(?:得)?写业务代码/, `${rel} 应禁写业务代码`);
    assert.match(body, /run\.mjs/, `${rel} 应经统一执行器运行`);
    assert.match(body, /fs\.rm/, `${rel} 应用 fs.rm 清理`);
    assert.match(body, /ACME_MVN/, `${rel} 应声明 Maven 零平台分支`);
  }
});

test('should_declare_manual_swarm_and_cross_platform_rules_in_code_review_skill', async () => {
  const skill = await read('.ai/skills/code-review/SKILL.md');
  assert.match(skill, /engine\.code-review: manual/, 'Skill 应声明 manual 档');
  assert.match(skill, /唯一(?:入库文件|产物) `contracts\/<REVIEW-ID>\.md`/, 'Skill 应声明唯一产物');
  assert.match(skill, /无命令入口/, 'Skill 应声明无命令入口');
  assert.match(skill, /不产生 REQ/, 'Skill 应声明不产生 REQ');
  for (const token of ['shell: false', 'ACME_MVN', 'fs.rm', 'process.execPath', '跨平台硬规范']) {
    assert.ok(skill.includes(token), `Skill 应含跨平台硬规范要点 ${token}`);
  }
  const pool = await read('.ai/agents/AGENT-POOL.md');
  assert.match(pool, /ai-coding-dev\//, 'AGENT-POOL 应登记 ai-coding-dev 蜂群');
  assert.match(pool, /code-review\//, 'AGENT-POOL 应登记 code-review 蜂群');
  const config = await read('.ai/ai-coding-config.yml');
  assert.match(config, /^  code-review: manual$/m, '引擎开关应配置 code-review: manual');
});
