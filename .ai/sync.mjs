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
 *   3) .ai/scopes/**    → 分层规则多工具分发：同路径 AGENTS.md + CLAUDE.md（@导入/链接）+
 *        GEMINI.md（@导入）+ .cursor/rules/scope-*.mdc（glob 自动附加）+ .github/instructions/scope-*.instructions.md（applyTo）
 *   4) .ai/agents|skills|commands/ → 跨工具公共池，拷贝派生到各工具目录（保证跨平台可移植）
 *   5) .ai/tools/<工具>/**         → 工具专属配置，派生到对应工具目录（doc 派生为目录说明）
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

/** 跨工具公共池：.ai/<src>/** → 各工具目录（doc 仅作源目录说明，不派生） */
const POOLS = [
  { src: 'agents', doc: 'AGENT-POOL.md', targets: ['.claude/agents', '.iflow/agents', '.gemini/agents'] },
  { src: 'skills', doc: 'SKILL-POOL.md', targets: ['.claude/skills', '.iflow/workflows'] },
  { src: 'commands', doc: 'COMMAND-POOL.md', targets: ['.claude/commands', '.iflow/commands'] },
];

/** 工具专属配置：.ai/tools/<key>/** → <target>/**（doc 派生为工具目录内的说明文件） */
const TOOLS = [
  { key: 'claude', doc: 'CLAUDE-CODE.md', target: '.claude' },
  { key: 'cursor', doc: 'CURSOR.md', target: '.cursor' },
  { key: 'gemini', doc: 'GEMINI-CLI.md', target: '.gemini' },
  { key: 'iflow', doc: 'IFLOW-CLI.md', target: '.iflow' },
  { key: 'trae', doc: 'TRAE.md', target: '.trae' },
  { key: 'qoder', doc: 'QODER.md', target: '.qoder' },
  { key: 'github', doc: 'GITHUB-CONFIG.md', target: '.github' },
];

/** 其他派生目标：.ai 内的源 → 仓库固定路径（GitHub Actions 只认 .github/workflows/） */
const EXTRA = [{ src: 'ci', target: '.github/workflows' }];

const banner = (tool) =>
  `<!-- ⚠️ 本文件由 .ai/sync.mjs 自动生成（服务对象：${tool}），请勿直接修改。\n     所有 AI 配置唯一事实源：.ai/ 。修改源文件后运行 node .ai/sync.mjs 重新生成。 -->`;
const footer = (src) =>
  `\n\n<!-- 派生自 ${src}（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->\n`;
/** 工具目录说明文件的统一标头（源文件无需手写，派生时自动前置，避免多处重复） */
const PLACARD_HEAD = `> ⚙️ 本目录为**生成产物**：所有 AI 配置统一维护于 [.ai/](../.ai/ARCHITECTURE.md)，由 [.ai/sync.mjs](../.ai/sync.mjs) 派生，请勿手改（本说明文件亦然）。\n\n`;
/** 派生文件标记：用于孤儿检测（源已删、派生物残留） */
const DERIVED_MARK = /派生自 \.ai\/|本文件由 \.ai\/sync\.mjs 自动生成/;

const norm = (s) => s.replace(/\r\n/g, '\n');
/** 链接目标规范化：Windows 上 readlink 可能把正斜杠规范成反斜杠 */
const normLink = (p) => p.split('\\').join('/');

/** 遍历时跳过的重型/无关目录（依赖、构建产物、临时区） */
const SKIP_DIRS = new Set(['.git', 'node_modules', 'target', 'dist', 'build', 'coverage', '.turbo', '.ai-tmp']);

async function walk(dir) {
  let entries = [];
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return [];
  }
  const files = [];
  for (const e of entries) {
    if (SKIP_DIRS.has(e.name)) continue;
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

// ---- 4. 分层规则：.ai/scopes/** → 每个作用域产出多种工具形态（多工具兼容） ----
// 同一份 AGENTS.md 内容分发为：AGENTS.md（标准族/Codex/Cursor 嵌套）· CLAUDE.md（Claude）·
// GEMINI.md（Gemini 层级）· .cursor/rules/*.mdc（按 glob 自动附加）· .github/instructions（Copilot applyTo）
const SCOPES = join(AI, 'scopes');
for (const f of await walk(SCOPES)) {
  const rel = relative(SCOPES, f).split('\\').join('/');
  if (rel === 'SCOPED-RULES.md') continue;
  const content = norm(await readFile(f, 'utf8'));
  const tagged = rel.endsWith('.md') ? content.trimEnd() + footer(`.ai/scopes/${rel}`) : content;
  expected.set(rel, tagged);

  if (/(^|\/)AGENTS\.md$/.test(rel)) {
    const scopeDir = rel.replace(/(^|\/)AGENTS\.md$/, '');
    if (!scopeDir) continue; // 根 AGENTS.md 由根部入口机制负责
    const slug = scopeDir.split('/').join('-');
    const label = `.ai/scopes/${rel}`;

    // CLAUDE.md：@ 导入存根（--links 模式下为符号链接）；带派生标记以便孤儿检测
    links.set(`${scopeDir}/CLAUDE.md`, {
      target: 'AGENTS.md',
      fallback: norm(
        `@AGENTS.md\n\n本目录的目录级规范见同目录 \`AGENTS.md\`（源：\`${label}\`）。\n${footer(label)}`
      ),
    });
    // GEMINI.md：Gemini CLI 层级记忆，@ 导入
    expected.set(
      `${scopeDir}/GEMINI.md`,
      norm(`@AGENTS.md\n\n本目录的目录级规范见同目录 \`AGENTS.md\`（源：\`${label}\`）。\n${footer(label)}`)
    );
    // Cursor：按 glob 自动附加的规则（alwaysApply: false）
    expected.set(
      `.cursor/rules/scope-${slug}.mdc`,
      norm(
        `---\ndescription: ${scopeDir}/ 目录规范（自动生成，源：${label}）\nglobs: ["${scopeDir}/**"]\nalwaysApply: false\n---\n\n${tagged}`
      )
    );
    // GitHub Copilot：按路径生效的指令
    expected.set(
      `.github/instructions/scope-${slug}.instructions.md`,
      norm(`---\napplyTo: "${scopeDir}/**"\n---\n\n${tagged}`)
    );
  }
}

// ---- 5. 公共池、工具专属、其他目标（逐文件派生） ----
async function derive(srcDir, outRoot, srcLabel, { excludeDocs = [], placardDoc = null }) {
  for (const f of await walk(srcDir)) {
    const rel = relative(srcDir, f).split('\\').join('/');
    if (excludeDocs.includes(rel)) continue;
    const label = `${srcLabel}${rel}`;
    let content = norm(await readFile(f, 'utf8'));
    if (placardDoc && rel === placardDoc) content = PLACARD_HEAD + content;
    if (rel.endsWith('.md')) content = content.trimEnd() + footer(label);
    else if (/\.(ya?ml|toml)$/.test(rel))
      content = `# 派生自 ${label}（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs\n\n` + content;
    expected.set(`${outRoot}/${rel}`, content);
  }
}

for (const p of POOLS) {
  for (const t of p.targets) {
    await derive(join(AI, p.src), t, `.ai/${p.src}/`, { excludeDocs: [p.doc] });
  }
}
for (const t of TOOLS) {
  await derive(join(AI, 'tools', t.key), t.target, `.ai/tools/${t.key}/`, { placardDoc: t.doc });
}
for (const e of EXTRA) {
  // CI-GUIDE.md 是目录说明，不派生到 .github/workflows/
  await derive(join(AI, e.src), e.target, `.ai/${e.src}/`, { excludeDocs: ['CI-GUIDE.md'] });
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

// ---- 6. 命名与预算约束（预算条款见 .ai/rules/ai-guidelines.md） ----
const RULES_BUDGET = 8192;
const rulesBytes = Buffer.byteLength(expected.get('.ai/RULES.md') ?? '', 'utf8');
if (rulesBytes > RULES_BUDGET) {
  errors++;
  console.error(`❌ .ai/RULES.md 超出预算：${rulesBytes}B > ${RULES_BUDGET}B（细节移入 .ai-memory/playbooks，规则只留结论+路径）`);
} else {
  console.log(`✅ 预算：RULES.md ${rulesBytes}B ≤ ${RULES_BUDGET}B`);
}

// 命名规则：全库仅根 README.md；第三方脚手架（vendored）原样保留
const VENDOR_README_PREFIXES = ['workspace/apps/frontend/web-admin/'];
for (const f of await walk(root)) {
  const rel = relative(root, f).split('\\').join('/');
  if (!/(^|\/)README\.md$/.test(rel) || rel === 'README.md') continue;
  if (VENDOR_README_PREFIXES.some((p) => rel.startsWith(p))) continue;
  errors++;
  console.error(`❌ 命名规则：${rel} 应改为实义命名（全库仅保留根 README.md）`);
}

// ---- 7. 孤儿派生文件检测：派生文件带标记但源已删除的残留 ----
const ORPHAN_ROOTS = [...TOOLS.map((t) => t.target), 'workspace'];
for (const rootDir of ORPHAN_ROOTS) {
  for (const f of await walk(join(root, rootDir))) {
    const rel = relative(root, f).split('\\').join('/');
    if (expected.has(rel) || links.has(rel)) continue;
    if (!/\.(md|mdc|instructions\.md)$/.test(rel)) continue;
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
