import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Gemini CLI 自定义命令守卫：.ai/tools/gemini/commands/req.toml 是 .ai/commands/req.md 的手工 TOML 转写
// （引擎不派生 TOML 命令），源演化后若 TOML 未跟上会静默过期——这里从源提取关键标记逐一验证 prompt。

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (rel) => readFile(join(root, rel), 'utf8');

const SOURCE = '.ai/commands/req.md';
const TOML = '.ai/tools/gemini/commands/req.toml';

// 关键标记：既确认源基线仍在（提取失效时显式失败），也确认 TOML prompt 覆盖源的关键结构。
const MARKERS = [
  'engine.ai-coding-dev',
  '四阶段',
  'contracts/{REQ-ID}.md',
  '开关自检',
  'multi-agent.md',
  'team-leader/SKILL.md',
  '流程状态',
  'AI 操作授权分级',
  'never',
];

test('should_keep_gemini_req_toml_prompt_in_sync_with_command_source', async () => {
  const source = await read(SOURCE);
  const toml = await read(TOML);
  for (const marker of MARKERS) {
    assert.ok(source.includes(marker), `${SOURCE} 应含关键标记 ${marker}（提取基线已过期，请更新守卫）`);
    assert.ok(toml.includes(marker), `${TOML} 的 prompt 应含源关键标记 ${marker}`);
  }
  // 契约路径两种写法等价：源用 {REQ-ID}，TOML 额外兼容 <REQ-ID>
  assert.match(toml, /contracts\/(?:\{|<)REQ-ID(?:\}|>)\.md/);
  // 第 0 步自检结构（源中记作「开关自检」）
  assert.match(toml, /第 0 步/);
  // Gemini 自定义命令格式要求：description 元数据 + $ARGUMENTS 占位
  assert.match(toml, /^description\s*=/m);
  assert.match(toml, /\$ARGUMENTS/);
});

test('should_keep_gemini_req_description_identical_to_command_source', async () => {
  const sourceDescription = (await read(SOURCE)).match(/^description:\s*(.*)$/m)[1].trim();
  const tomlDescription = (await read(TOML)).match(/^description\s*=\s*"([^"]*)"$/m)[1];
  assert.equal(tomlDescription, sourceDescription, `${TOML} 的 description 必须与 ${SOURCE} 逐字一致`);
});
