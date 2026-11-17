#!/usr/bin/env node
/**
 * check-contract.mjs — 契约机器层校验门禁（team-leader 多代理流程 III-GATE 第 3 项）。
 *
 * 用法：node .ai/skills/team-leader/scripts/check-contract.mjs contracts/{REQ-ID}.md
 * 输出：[GATE] 协议行（name=contract-valid result=PASS|FAIL detail=...），退出码 0/1。
 * 校验的是 frontmatter 机器层与正文骨架；语义正确性（AC 是否可断言等）由 TL 与用户把关。
 */
import { readFile } from 'node:fs/promises';

const file = process.argv[2];
if (!file) {
  console.error('[GATE] name=contract-valid result=FAIL detail=缺少契约文件参数（用法: node .ai/skills/team-leader/scripts/check-contract.mjs contracts/{REQ-ID}.md）');
  process.exit(1);
}

let text;
try {
  text = await readFile(file, 'utf8');
} catch {
  console.error(`[GATE] name=contract-valid result=FAIL detail=契约文件不可读: ${file}`);
  process.exit(1);
}

const errors = [];
const m = text.match(/^---\r?\n([\s\S]*?)\r?\n---\s*\n/);
if (!m) {
  errors.push('缺少 frontmatter');
} else {
  const fm = m[1];
  for (const key of ['requirementId', 'title', 'status', 'branchContext', 'migrations', 'apis']) {
    if (!new RegExp(`^${key}:`, 'm').test(fm)) errors.push(`frontmatter 缺 ${key}`);
  }
  if (!/^requirementId:\s*"?(REQ-\d{8}-\d{6})"?/m.test(fm)) errors.push('requirementId 不符合 REQ-YYYYMMDD-HHMMSS');
  if (/^status:\s*draft/m.test(fm)) errors.push('status 仍为 draft（未经用户逐项确认禁止进入开发）');
  else if (!/^status:\s*confirmed/m.test(fm)) errors.push('status 必须为 draft|confirmed');
  const featureBranch = fm.match(/^  featureBranch:\s*"?(feat\/req-\d{8}-\d{6}-[a-z0-9-]+)"?/m);
  if (!featureBranch) errors.push('featureBranch 不符合 feat/req-{YYYYMMDD}-{HHMMSS}-{slug}');
}
const body = m ? text.slice(m[0].length) : text;
if (!/^-\s+AC-\d+\s+/m.test(body)) errors.push('验收标准节无 AC 条目');
if (!/^##\s+流程状态/m.test(body)) errors.push('缺少「流程状态」节');

console.log('[GATE] name=contract-valid result=' + (errors.length ? `FAIL detail=${errors.join('; ')}` : 'PASS'));
process.exit(errors.length ? 1 : 0);
