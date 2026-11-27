import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// AI 路径单一清单回归（ADR 2026-11-17-01）：清单曾手工维护于 pre-commit（shell）、mirror-check.mjs（JS）、
// ci.yml mirror-sync（YAML 内联）三处，已发生真实漂移（.codebuddy 漏网）——现收敛为 .ai/hooks/ai-paths.list
// 唯一事实源，pre-commit 与 mirror-check 直接消费，ci.yml 保留内联副本并由 sync.mjs 强制一致。
// 本文件是引擎之外的第二道断言：三处消费方式与清单内容在此回归锁定。

const root = fileURLToPath(new URL('../../', import.meta.url));
const LIST_FILE = join(root, '.ai/hooks/ai-paths.list');
const CI_FILE = join(root, '.ai/ci/ci.yml');

const parseList = (text) =>
  text
    .split('\n')
    .map((l) => l.replace(/\r$/, '').trim())
    .filter((l) => l && !l.startsWith('#'));

// 与 .ai/sync.mjs 的 extractCiInlineAiPaths 同口径（双重锁定：引擎不一致即失败，这里断言字段形态）
const extractInline = (ciText) => {
  const m = ciText.match(/AI_PATHS=\(\n([\s\S]*?)\n[ \t]*\)/);
  assert.ok(m, '.ai/ci/ci.yml 应包含 mirror-sync 作业的 AI_PATHS=(...) 内联清单');
  return m[1]
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'))
    .join('\n')
    .split(/\s+/)
    .map((t) => (t.startsWith("'") && t.endsWith("'") ? t.slice(1, -1) : t));
};

test('should_keep_ci_inline_ai_paths_identical_to_single_list', async () => {
  const list = parseList(await readFile(LIST_FILE, 'utf8'));
  assert.ok(list.length >= 15, `清单应含全部既有路径（当前 ${list.length} 条）`);
  const inline = extractInline(await readFile(CI_FILE, 'utf8'));
  assert.deepEqual(inline, list, 'ci.yml 内联清单必须与 ai-paths.list 逐条（含顺序）一致');
});

test('should_cover_core_entries_and_exclude_retired_and_local_only_paths', async () => {
  const list = parseList(await readFile(LIST_FILE, 'utf8'));
  for (const p of ['.ai', '.ai-memory', '.github', '.agents', '.gitignore', 'AGENTS.md', 'CLAUDE.md', ':(glob)**/AGENTS.md', ':(glob)**/CLAUDE.md']) {
    assert.ok(list.includes(p), `清单应包含 ${p}`);
  }
  const joined = list.join('\n');
  assert.doesNotMatch(joined, /\.iflow/, 'iFlow 已裁撤（ADR 2026-11-14-21），清单不得含 .iflow');
  assert.doesNotMatch(joined, /\.zcode|\.codebuddy/, 'localOnly 本机目标（git 忽略、不入库）不入镜像清单');
  // 清单自身位于 .ai/hooks/ 下：由首条 .ai 路径覆盖其「必须完整暂存」检查（自举覆盖）
  assert.equal(list[0], '.ai');
});

test('should_consume_single_list_in_precommit_and_mirror_check', async () => {
  const preCommit = await readFile(join(root, '.ai/hooks/pre-commit'), 'utf8');
  assert.match(preCommit, /ai-paths\.list/, 'pre-commit 应读取 AI 路径单一清单');
  assert.match(preCommit, /while IFS= read -r line/, 'pre-commit 应逐行读取清单');
  const mirrorCheck = await readFile(join(root, '.ai/hooks/mirror-check.mjs'), 'utf8');
  assert.match(mirrorCheck, /ai-paths\.list/, 'mirror-check.mjs 应读取 AI 路径单一清单');
  assert.doesNotMatch(mirrorCheck, /'\.ai-memory'/, 'mirror-check.mjs 不得再内联硬编码路径数组');
});
