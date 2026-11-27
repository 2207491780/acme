#!/usr/bin/env node
// 派生自 .ai/skills/code-review/scripts/validate-report.mjs（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs
/**
 * validate-report.mjs — code-review 唯一报告的机器结构校验（只读，不改任何文件）。
 *
 * 用法：node validate-report.mjs <报告路径>
 * 校验：七节 `## 1.`～`## 7.` 齐全；第 3 节 P0/P1/P2/P3 小节存在且每条意见含
 *       origin: / 证据 / 建议 / 归属 / 文件:行 样式；第 6 节有 L1/L2 行且失败或超时项
 *       标 SKIP(原因)；第 7 节有轮次记录。
 * 输出：PASS/FAIL 与缺失项；退出码 0=PASS / 1=FAIL / 2=用法或读取失败。
 * 硬规范：只用 node 内置模块；路径经 path.resolve；不写文件、不拼命令。
 */
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const [reportPath, ...extra] = process.argv.slice(2);
if (!reportPath || extra.length) {
  console.log('FAIL 用法: node validate-report.mjs <报告路径>');
  process.exit(2);
}

let text;
try {
  text = await readFile(resolve(reportPath), 'utf8');
} catch (error) {
  console.log(`FAIL 无法读取报告 ${reportPath}: ${error.message}`);
  process.exit(2);
}

const missing = [];
const headings = [...text.matchAll(/^## (\d)\. [^\n]*$/gm)];
const sections = new Map();
headings.forEach((heading, index) => {
  const start = heading.index + heading[0].length;
  const end = index + 1 < headings.length ? headings[index + 1].index : text.length;
  sections.set(heading[1], text.slice(start, end));
});

for (let number = 1; number <= 7; number += 1) {
  if (!sections.has(String(number))) missing.push(`缺第 ${number} 节（## ${number}.）`);
}

const opinions = sections.get('3') ?? '';
for (const level of ['P0', 'P1', 'P2', 'P3']) {
  const block = opinions.match(new RegExp(`^### ${level}(?![A-Za-z0-9])[^\\n]*\\n([\\s\\S]*?)(?=^### |^## |$(?![\\s\\S]))`, 'm'));
  if (!block) {
    missing.push(`第 3 节缺 ### ${level} 小节`);
    continue;
  }
  const items = block[1].split('\n').filter((line) => /^\s*(?:[-*+]|\d+[.)])\s+\S/.test(line));
  if (!items.length) missing.push(`### ${level} 小节无可校验意见条目`);
  items.forEach((item, index) => {
    const tag = `${level} 第 ${index + 1} 条`;
    if (!/origin\s*:/.test(item)) missing.push(`${tag} 缺 origin: 字段`);
    if (!/证据/.test(item)) missing.push(`${tag} 缺 证据 字段`);
    if (!/建议/.test(item)) missing.push(`${tag} 缺 建议 字段`);
    if (!/归属/.test(item)) missing.push(`${tag} 缺 归属 字段`);
    if (!/[\w.\/-]+\.\w+:\d+/.test(item)) missing.push(`${tag} 缺 文件:行 样式证据`);
  });
}

const evidence = sections.get('6') ?? '';
if (!/^\|\s*L1\s*\|/m.test(evidence)) missing.push('第 6 节缺 L1 证据行');
if (!/^\|\s*L2\s*\|/m.test(evidence)) missing.push('第 6 节缺 L2 证据行');
if (/(FAIL|失败|超时|SIGTERM|ETIMEDOUT)/.test(evidence) && !/SKIP\(/.test(evidence)) {
  missing.push('第 6 节存在失败/超时项但未标 SKIP(原因)');
}

if (!/^###\s*第\s*\d+\s*轮/m.test(sections.get('7') ?? '')) {
  missing.push('第 7 节缺轮次记录（### 第 N 轮）');
}

if (missing.length) {
  console.log(`FAIL: ${reportPath}`);
  for (const item of missing) console.log(`- ${item}`);
  process.exit(1);
}

console.log(`PASS: ${reportPath}（七节齐全；P0-P3 条目字段完整；第 6 节 L1/L2 证据与 SKIP 口径符合；第 7 节轮次记录存在）`);
