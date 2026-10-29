#!/usr/bin/env node
/**
 * sync.mjs — AI Coding 配置统一派生引擎（位于唯一事实源 .ai/ 内）。
 *
 * 所有 AI-Coding 内容只允许在 .ai/ 下修改；本脚本负责派生与校验：
 *   1) .ai/rules/*.md   → 拼接为唯一完整副本 .ai/RULES.md
 *   2) 根部入口 AGENTS.md / CLAUDE.md / GEMINI.md / CODEBUDDY.md
 *      → 默认为「引用存根」（~1KB：必读指引 + 铁律节选，指向 .ai/RULES.md）；
 *        可选 --links（或环境变量 AI_SYNC_LINK=1）切换为符号链接模式（磁盘零拷贝，
 *        但编辑器中会显示为全文；在不支持 symlink 的机器上会被 git 检出成损坏文本，sync 可自愈）
 *   3) .ai/scopes/**    → 分层 AGENTS.md：按同路径派生到仓库对应目录（就近生效），
 *        并自动在同级生成 CLAUDE.md 引用存根（默认）或链接（--links）
 *   4) .ai/agents|skills|commands/ → 跨工具公共池，拷贝派生到各工具目录（保证跨平台可移植）
 *   5) .ai/tools/<工具>/**         → 工具专属配置，派生到对应工具目录
 *   6) .ai/ci/**       → 派生到 .github/workflows/（GitHub 要求固定路径）
 *
 * 用法：
 *   node .ai/sync.mjs          # 同步：重新生成所有派生文件 / 链接
 *   node .ai/sync.mjs --check  # 校验：不写入；逐文件比对 + 链接检查 + 孤儿检测
 *
 * 联动铁律（.ai/rules/ai-guidelines.md「AI 配置联动更新」）：
 *   修改 .ai/ 必须触发所有工具联动更新，禁止手改任何派生文件。
 */
import { readFile, writeFile, mkdir, readdir, lstat, symlink, readlink, unlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const AI = join(root, '.ai');
const CHECK = process.argv.includes('--check');

/** 根部入口：默认引用存根（指向 .ai/RULES.md）；--links 模式为符号链接 */
const LINK_TARGETS = [
  { tool: 'OpenAI Codex、智谱 ZCode 及所有遵循 AGENTS.md 标准的工具', file: 'AGENTS.md' },
  { tool: 'Anthropic Claude Code', file: 'CLAUDE.md', claudeImport: true },
  { tool: 'Google Gemini CLI / Antigravity', file: 'GEMINI.md' },
  { tool: '腾讯 CodeBuddy', file: 'CODEBUDDY.md' },
];
const LINK_TO = '.ai/RULES.md';

/** 规则入口存根（需专属格式 / frontmatter / 长度考虑，不做链接） */
const STUB_TARGETS = [
  // Copilot 指令有长度限制：存根极小（引用 .ai/RULES.md），反而规避了截断问题
  { tool: 'GitHub Copilot', file: '.github/copilot-instructions.md' },
  { tool: '字节 Trae', file: '.trae/rules/project_rules.md' },
  {
    // Qoder 依靠 frontmatter 决定规则激活方式：alwaysApply: true 才会每次会话常驻加载
    tool: '阿里 Qoder',
    file: '.qoder/rules/project_rules.md',
    frontmatter: ['---', 'description: ACME 项目规范入口（自动生成，引用 .ai/RULES.md）', 'alwaysApply: true', '---'],
  },
  {
    tool: 'Cursor',
    file: '.cursor/rules/project.mdc',
    frontmatter: ['---', 'description: ACME 项目规范入口（自动生成，引用 .ai/RULES.md）', 'alwaysApply: true', '---'],
  },
];

/** 跨工具公共池：.ai/<src>/** → 各工具目录（README.md 仅作源目录说明，不派生） */
const POOLS = [
  { src: 'agents', targets: ['.claude/agents', '.iflow/agents', '.gemini/agents'] },
  { src: 'skills', targets: ['.claude/skills', '.iflow/workflows'] },
  { src: 'commands', targets: ['.claude/commands', '.iflow/commands'] },
];

/** 工具专属配置：.ai/tools/<key>/** → <target>/**（README.md 派生为工具目录内的说明文件） */
const TOOLS = [
  { key: 'claude', target: '.claude' },
  { key: 'cursor', target: '.cursor' },
  { key: 'gemini', target: '.gemini' },
  { key: 'iflow', target: '.iflow' },
  { key: 'trae', target: '.trae' },
  { key: 'qoder', target: '.qoder' },
  { key: 'github', target: '.github' },
];

/** 其他派生目标：.ai 内的源 → 仓库固定路径（GitHub Actions 只认 .github/workflows/） */
const EXTRA = [{ src: 'ci', target: '.github/workflows' }];

const banner = (tool) =>
  `<!-- ⚠️ 本文件由 .ai/sync.mjs 自动生成（服务对象：${tool}），请勿直接修改。\n     所有 AI 配置唯一事实源：.ai/ 。修改源文件后运行 node .ai/sync.mjs 重新生成。 -->`;
const footer = (src) =>
  `\n\n<!-- 派生自 ${src}（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->\n`;
/** 工具目录说明文件的统一标头（源 README 无需手写，派生时自动前置，避免 7 处重复） */
const PLACARD_HEAD = `> ⚙️ 本目录为**生成产物**：所有 AI 配置统一维护于 [.ai/](../.ai/README.md)，由 [.ai/sync.mjs](../.ai/sync.mjs) 派生，请勿手改（本说明文件亦然）。\n\n`;
/** 派生文件标记：用于孤儿检测（源已删、派生物残留） */
const DERIVED_MARK = /派生自 \.ai\/|本文件由 \.ai\/sync\.mjs 自动生成/;

const norm = (s) => s.replace(/\r\n/g, '\n');
/** 链接目标规范化：Windows 上 readlink 可能把正斜杠规范成反斜杠 */
const normLink = (p) => p.split('\\').join('/');

async function walk(dir) {
  let entries = [];
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return [];
  }
  const files = [];
  for (const e of entries) {
    const p = join(dir, e.name);
    if (e.isDirectory()) files.push(...(await walk(p)));
    else files.push(p);
  }
  return files;
}

// ---- 0. 探测当前环境是否支持符号链接（探针放在系统临时目录，不污染仓库） ----
async function canSymlink() {
  const probe = join(tmpdir(), `acme-symlink-probe-${process.pid}`);
  try {
    await symlink('probe-target', probe);
    await unlink(probe);
    return true;
  } catch {
    await unlink(probe).catch(() => {});
    return false;
  }
}
/** 链接模式为可选（默认引用存根）：--links 或环境变量 AI_SYNC_LINK=1 时启用 */
const USE_LINKS = process.argv.includes('--links') || process.env.AI_SYNC_LINK === '1';
const SYMLINK_OK = USE_LINKS ? await canSymlink() : false;

/** 期望集：根相对路径（正斜杠）→ 规范化内容 */
const expected = new Map();
/** 链接集：根相对路径 → { target, fallback }（symlink 可用时用链接，否则写 fallback 内容） */
const links = new Map();

// ---- 1. 公共规范：拼接为唯一完整副本 .ai/RULES.md ----
let body = '';
for (const f of (await walk(join(AI, 'rules'))).sort()) {
  if (f.endsWith('README.md')) continue;
  body += norm(await readFile(f, 'utf8')).trim() + '\n\n';
}
if (!body.trim()) {
  console.error('❌ .ai/rules/ 下没有任何规则源文件，中止。');
  process.exit(1);
}
const combinedBanner =
  '<!-- ⚠️ 本文件由 .ai/sync.mjs 从 .ai/rules/*.md 自动拼接生成，请勿直接修改；修改请编辑 .ai/rules/ 下的源文件并重新运行脚本。 -->';
expected.set('.ai/RULES.md', norm(`${combinedBanner}\n\n# ACME 项目规范\n\n${body.trim()}\n`));

// ---- 2. 根部入口：默认引用存根（Claude Code 的存根用 @import 自动内联全文） ----
const stub = `本仓库所有 AI 规范统一维护于 \`.ai/\`。**开始任何任务前，必须先完整阅读 \`.ai/RULES.md\`** —— 它是公共规范（AI 协作准则、编码规范、Git 工作流、项目上下文）的唯一完整副本。

## 铁律（节选，完整版见 .ai/RULES.md）

1. 所有 AI 配置的唯一事实源是 \`.ai/\`，禁止直接修改任何工具目录下的派生文件。
2. 修改 \`.ai/\` 后必须立即运行 \`node .ai/sync.mjs\` 派生到所有工具，并通过 \`node .ai/sync.mjs --check\` 校验，否则提交会被 pre-commit 钩子与 CI 拒绝。
3. 未读 \`.ai/RULES.md\` 前，不得开始任何编码任务。
4. 凡修改 AI-Coding 配置（规范 / 分层规则 / 公共池 / 工具配置 / 钩子 / CI）：先读 \`.ai-memory/playbooks/ai-config.md\`，按其五步流程执行。`;

for (const t of LINK_TARGETS) {
  const imp = t.claudeImport ? '@.ai/RULES.md\n\n' : '';
  links.set(t.file, {
    target: LINK_TO,
    fallback: norm(`${banner(t.tool)}\n\n${imp}# ACME 项目规范（入口）\n\n${stub}\n`),
  });
}

// ---- 3. 需要专属格式的规则入口：生成存根 ----
for (const t of STUB_TARGETS) {
  const front = t.frontmatter ? t.frontmatter.join('\n') + '\n\n' : '';
  expected.set(t.file, norm(`${front}${banner(t.tool)}\n\n# ACME 项目规范（入口）\n\n${stub}\n`));
}

// ---- 4. 分层 AGENTS.md：.ai/scopes/** → 仓库同路径 + 同级 CLAUDE.md ----
const SCOPES = join(AI, 'scopes');
for (const f of await walk(SCOPES)) {
  const rel = relative(SCOPES, f).split('\\').join('/');
  if (rel === 'README.md') continue;
  let content = norm(await readFile(f, 'utf8'));
  if (rel.endsWith('.md')) content = content.trimEnd() + footer(`.ai/scopes/${rel}`);
  expected.set(rel, content);
  if (/(^|\/)AGENTS\.md$/.test(rel)) {
    // 同级 CLAUDE.md：默认为 @ 导入存根（引用同目录 AGENTS.md）；--links 模式为符号链接
    links.set(rel.replace(/AGENTS\.md$/, 'CLAUDE.md'), {
      target: 'AGENTS.md',
      fallback: norm(
        `@AGENTS.md\n\n本目录的目录级规范在同目录 \`AGENTS.md\`（唯一事实源：\`.ai/scopes/${rel}\`）。\n`
      ),
    });
  }
}

// ---- 5. 公共池、工具专属、其他目标（逐文件派生） ----
async function derive(srcDir, outRoot, srcLabel, { excludeReadme, placard = false }) {
  for (const f of await walk(srcDir)) {
    const rel = relative(srcDir, f).split('\\').join('/');
    if (excludeReadme && rel === 'README.md') continue;
    const label = `${srcLabel}${rel}`;
    let content = norm(await readFile(f, 'utf8'));
    if (placard && rel === 'README.md') content = PLACARD_HEAD + content;
    if (rel.endsWith('.md')) content = content.trimEnd() + footer(label);
    else if (/\.(ya?ml|toml)$/.test(rel))
      content = `# 派生自 ${label}（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs\n\n` + content;
    expected.set(`${outRoot}/${rel}`, content);
  }
}

for (const p of POOLS) {
  for (const t of p.targets) {
    await derive(join(AI, p.src), t, `.ai/${p.src}/`, { excludeReadme: true });
  }
}
for (const t of TOOLS) {
  await derive(join(AI, 'tools', t.key), t.target, `.ai/tools/${t.key}/`, { excludeReadme: false, placard: true });
}
for (const e of EXTRA) {
  // excludeReadme：防止未来在 .ai/ci/ 放说明文件被派生成 .github/workflows/README.md
  await derive(join(AI, e.src), e.target, `.ai/${e.src}/`, { excludeReadme: true });
}

// ---- 6. 写入 / 校验 ----
let errors = 0;

async function ensureContent(rel, want) {
  const abs = join(root, rel);
  let cur = null;
  try {
    cur = norm(await readFile(abs, 'utf8'));
  } catch {
    // 不存在视为缺失
  }
  if (CHECK) {
    if (cur === null) {
      errors++;
      console.error(`❌ 缺失 ${rel}`);
    } else if (cur !== want) {
      errors++;
      console.error(`❌ ${rel} 与 .ai/ 不同步`);
    } else {
      console.log(`✅ ${rel}`);
    }
    return;
  }
  await mkdir(dirname(abs), { recursive: true });
  await writeFile(abs, want, 'utf8');
  console.log(`✅ 已生成 ${rel}`);
}

for (const [rel, { target, fallback }] of links) {
  const abs = join(root, rel);
  const st = await lstat(abs).catch(() => null);

  if (CHECK) {
    // 链接（目标正确）或降级内容（匹配）任一通过
    if (st?.isSymbolicLink()) {
      const cur = normLink((await readlink(abs).catch(() => '')) ?? '');
      if (cur === target) console.log(`✅ ${rel} → ${target}`);
      else {
        errors++;
        console.error(`❌ ${rel} 链接目标错误（${cur}，应为 ${target}）`);
      }
    } else {
      await ensureContent(rel, fallback);
    }
    continue;
  }

  if (USE_LINKS && SYMLINK_OK) {
    const cur = st?.isSymbolicLink() ? normLink((await readlink(abs).catch(() => '')) ?? '') : null;
    if (cur === target) {
      console.log(`🔗 ${rel} → ${target}（已存在）`);
    } else {
      if (st) await rm(abs, { recursive: true, force: true });
      await mkdir(dirname(abs), { recursive: true });
      await symlink(target, abs);
      console.log(`🔗 已链接 ${rel} → ${target}`);
    }
  } else {
    // 默认模式：写引用存根（同时会把已有的链接替换为存根，修复 git 检出损坏的链接文本）
    let cur = null;
    try {
      cur = st?.isSymbolicLink() ? null : norm(await readFile(abs, 'utf8'));
    } catch {
      // 读不到按缺失处理
    }
    if (st?.isSymbolicLink() || cur !== fallback) {
      if (st) await rm(abs, { recursive: true, force: true });
    }
    await ensureContent(rel, fallback);
  }
}

for (const [rel, want] of expected) {
  await ensureContent(rel, want);
}

// ---- 7. 孤儿派生文件检测：工具目录中带派生标记、但已不在期望集内的残留 ----
for (const t of TOOLS) {
  for (const f of await walk(join(root, t.target))) {
    const rel = relative(root, f).split('\\').join('/');
    if (expected.has(rel)) continue;
    let txt = '';
    try {
      txt = await readFile(f, 'utf8');
    } catch {
      continue; // 二进制等无法按文本读取的文件不检测
    }
    if (DERIVED_MARK.test(txt)) {
      errors++;
      console.error(`❌ 孤儿派生文件 ${rel}（源已删除？请手动移除）`);
    }
  }
}

if (errors > 0) {
  console.error(
    `\n❌ ${errors} 处未与 .ai/ 联动（禁止只同步部分工具）。\n   修复：node .ai/sync.mjs，处理后重新提交。`
  );
  process.exit(1);
}
console.log(
  CHECK
    ? `\n校验通过：${expected.size + links.size} 个派生文件/链接均与 .ai/ 同步，无孤儿残留。`
    : `\n完成：${expected.size} 个文件已派生、${links.size} 个入口已处理（${
        USE_LINKS && SYMLINK_OK ? '符号链接模式（--links）' : '引用存根模式（默认；--links 可切换为符号链接）'
      }）。`
);
