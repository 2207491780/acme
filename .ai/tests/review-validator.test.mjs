import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { lstat, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// 报告结构校验器回归：用 .ai-tmp/ 下 fixture 验证 PASS/FAIL，结束用 fs.rm 清理。
// 与 .ai/tests/*.test.mjs 风格一致：不写仓库内产物、不碰业务报告。

const root = fileURLToPath(new URL('../../', import.meta.url));
const VALIDATOR = join(root, '.ai/skills/code-review/scripts/validate-report.mjs');

const GOOD = `# REVIEW-20261127-000000 全面代码评审报告

## 1. 评审结论
- 结论：APPROVE

## 2. 评审输入
- 评审号：REVIEW-20261127-000000
- 范围：示例模块

## 3. 分级意见
### P0
1. \`foo/Bar.java:12\` | 安全 | 越权 | 证据：\`foo/Bar.java:12-14\` | 建议：补鉴权 | origin: new | 归属：BE
### P1
1. \`foo/Baz.java:20\` | 正确性 | 边界 | 证据：\`foo/Baz.java:20\` | 建议：补校验 | origin: new | 归属：BE
### P2
1. \`web/a.ts:8\` | 性能 | N+1 | 证据：\`web/a.ts:8-10\` | 建议：批量化 | origin: historical | 归属：FE
### P3
1. \`core/b.ts:5\` | 风格 | 命名 | 证据：\`core/b.ts:5\` | 建议：改名 | origin: new | 归属：QR

## 4. 链路仿真与副作用
- 无新增链路。

## 5. 历史债隔离
- origin=historical 不计入结论。

## 6. 证据清单
| 层级 | 命令 | 输出摘录 | 结果 |
|---|---|---|---|
| L0 | Read/Grep | file:line | PASS |
| L1 | pnpm typecheck | ok | PASS |
| L2 | probe.mjs | 无运行态环境 | SKIP(无环境) |

## 7. 评审轮次记录
### 第 1 轮
- 结论：APPROVE
`;

async function sandboxFor(t) {
  const temp = join(root, '.ai-tmp');
  const stat = await lstat(temp);
  assert.ok(stat.isDirectory() && !stat.isSymbolicLink(), 'fixtures must stay in repository .ai-tmp/');
  await mkdir(temp, { recursive: true });
  const sandbox = await mkdtemp(join(temp, 'review-validator-test-'));
  t.after(() => rm(sandbox, { recursive: true, force: true }));
  return sandbox;
}

function check(file) {
  return spawnSync(process.execPath, [VALIDATOR, file], { cwd: root, encoding: 'utf8' });
}

async function fixture(t, name, text) {
  const sandbox = await sandboxFor(t);
  const file = join(sandbox, name);
  await writeFile(file, text, 'utf8');
  return check(file);
}

test('should_accept_complete_report_when_all_sections_and_fields_present', async (t) => {
  const result = await fixture(t, 'good.md', GOOD);
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /^PASS:/m);
});

test('should_reject_report_when_a_required_section_is_missing', async (t) => {
  const broken = GOOD.replace(/## 5\.[\s\S]*?(?=## 6\.)/, '');
  const result = await fixture(t, 'no-section.md', broken);
  assert.equal(result.status, 1, result.stdout);
  assert.match(result.stdout, /FAIL:/);
  assert.match(result.stdout, /缺第 5 节/);
});

test('should_reject_report_when_an_opinion_lacks_origin_or_file_line', async (t) => {
  const noOrigin = GOOD.replace('origin: new | 归属：BE', 'origin new | 归属：BE');
  const first = await fixture(t, 'no-origin.md', noOrigin);
  assert.equal(first.status, 1, first.stdout);
  assert.match(first.stdout, /P0 第 1 条 缺 origin:/);

  const noFile = GOOD.replace(/\`foo\/Bar\.java:12\` \| 安全/g, 'foo/Bar.java | 安全').replace(/证据：\`foo\/Bar\.java:12-14\`/g, '证据：foo/Bar.java');
  const second = await fixture(t, 'no-file-line.md', noFile);
  assert.equal(second.status, 1, second.stdout);
  assert.match(second.stdout, /缺 文件:行 样式证据/);
});

test('should_reject_report_when_failed_evidence_has_no_skip_mark', async (t) => {
  const noSkip = GOOD.replace('SKIP(无环境)', 'FAIL(超时未取得输出)');
  const result = await fixture(t, 'no-skip.md', noSkip);
  assert.equal(result.status, 1, result.stdout);
  assert.match(result.stdout, /未标 SKIP\(原因\)/);
});
