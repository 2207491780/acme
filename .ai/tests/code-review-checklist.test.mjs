import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// 评审清单结构回归：四层分级、用户关键词族逐项命中、每条有角色归属与 CI/L1 去向、
// 无新增依赖声明。只读真实源，不建 fixture、不写任何文件（与 .ai/tests/*.test.mjs 风格一致）。

const root = fileURLToPath(new URL('../../', import.meta.url));
const CHECKLIST = '.ai/skills/code-review/review-checklist.md';
const read = (rel) => readFile(join(root, rel), 'utf8');

test('should_cover_four_tiers_and_all_user_keyword_families_in_review_checklist', async () => {
  const text = await read(CHECKLIST);
  for (const tier of ['Tier0', 'Tier1', 'Tier2', 'Tier3']) {
    assert.match(text, new RegExp(`## ${tier}\\b`), `清单应含 ${tier} 层`);
  }
  const keywords = [
    '架构', '设计', '依赖', '模块职责', '单一职责', '开闭', '循环依赖', '依赖引入',
    '第三方选型', '配置分离', '复用', '扩展点', '技术债', '分层边界', '业务逻辑',
    '功能正确性', '安全', '性能', '并发', '数据库', '异常日志', '编码规范',
    '接口', 'RPC', 'MQ', '测试', '配置', '部署', '前端', '移动', '文档',
    '可观测', '合规', '审计', '兜底风险',
  ];
  for (const keyword of keywords) {
    assert.ok(text.includes(keyword), `清单应覆盖用户关键词「${keyword}」`);
  }
  assert.match(text, /核心功能正确性/, '应注明用户 6 类概要已并入四层');
  assert.match(text, /质量健壮性[\s\S]*设计可维护性[\s\S]*安全性能[\s\S]*测试文档[\s\S]*工程规范流程/, '6 类概要应完整注明');
});

test('should_assign_every_checklist_row_a_role_and_ci_l1_destination', async () => {
  const text = await read(CHECKLIST);
  const tiers = text.split(/^## Tier\d[^\n]*$/m).slice(1);
  assert.equal(tiers.length, 4, '应恰有四层 Tier 节');
  let rows = 0;
  for (const section of tiers) {
    const lines = section.split('\n').filter((line) => line.startsWith('|') && !/^\|[-\s:|]+\|$/.test(line) && !line.startsWith('| 检查要点'));
    assert.ok(lines.length > 0, '每层应有检查表行');
    for (const line of lines) {
      const cells = line.split('|').slice(1, -1).map((cell) => cell.trim());
      assert.equal(cells.length, 4, `清单行应为四列：${line}`);
      assert.ok(cells[0].length > 0, `清单行应写检查要点：${line}`);
      assert.ok(/\.md|file:line|命令输出|file:/.test(cells[1]), `清单行应给证据形态：${line}`);
      assert.ok(/[A-Z]{2}(?:\s*\/\s*[A-Z]{2})*/.test(cells[2]), `清单行应给负责角色：${line}`);
      assert.match(cells[3].replace(/^\*+|\*+$/g, ''), /^(是|否|部分)/, `清单行应给 CI/L1 去向：${line}`);
      rows += 1;
    }
  }
  assert.ok(rows >= 20, `四层合计检查行应足够覆盖，实际 ${rows}`);
});

test('should_keep_tier3_as_ci_l1_only_without_new_dependencies_or_fake_lint', async () => {
  const text = await read(CHECKLIST);
  assert.match(text, /Tier0 最高/, '应声明 Tier0 最高');
  assert.match(text, /一票否决/, '应声明安全/架构重大缺陷一票否决');
  assert.match(text, /禁止新增依赖/, '不得引入新依赖');
  assert.match(text, /不得把 typecheck 或测试通过表述为 lint 通过/, '不得把类型检查/测试说成 lint');
  assert.match(text, /ESLint \/ axe 不在锁文件/, '应记录 ESLint/axe 不在锁文件的事实');
  assert.match(text, /交 L1/, 'Tier3 应标交 L1');
  assert.match(text, /## 角色映射/, '应含角色映射');
});
