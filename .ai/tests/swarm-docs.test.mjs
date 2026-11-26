import assert from 'node:assert/strict';
import { lstat, readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// 蜂群文档口径回归：上一轮审计发现三处漂移——工人角色仍称 Team Leader、leader description 用 ≠never
// 且与 ZCode 存根措辞不一、第 0 步自检漏了「未配置（缺省）」停用分支。本文件只读真实仓库源文件，
// 不建 fixture、不写任何文件（与 .ai/tests/*.test.mjs 风格一致）。

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (rel) => readFile(join(root, rel), 'utf8');

const BACKEND = '.ai/agents/ai-coding-dev/backend-dev.md';
const FRONTEND = '.ai/agents/ai-coding-dev/frontend-dev.md';
const LEADER = '.ai/agents/ai-coding-dev/ai-coding-dev-leader.md';
const ZCODE_LEADER = '.ai/tools/zcode/agents/ai-coding-dev-leader.md';
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

test('should_keep_leader_descriptions_identical_when_zcode_stub_mirrors_public_pool', async () => {
  const sourceDescription = descriptionOf(await read(LEADER), LEADER);
  const stubDescription = descriptionOf(await read(ZCODE_LEADER), ZCODE_LEADER);
  assert.equal(stubDescription, sourceDescription, `${ZCODE_LEADER} 的 description 必须与 ${LEADER} 逐字一致`);
  for (const [rel, description] of [[LEADER, sourceDescription], [ZCODE_LEADER, stubDescription]]) {
    assert.match(description, /auto\|always/, `${rel} 的 description 应含 auto|always 档位`);
    assert.match(description, /never 或缺省时停用/, `${rel} 的 description 应含「never 或缺省时停用」口径`);
    assert.doesNotMatch(description, /≠never/, `${rel} 的 description 不得再用 ≠never 旧口径`);
  }
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
