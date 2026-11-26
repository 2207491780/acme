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
import { readFile, writeFile, mkdir, mkdtemp, readdir, lstat, symlink, readlink, unlink, rmdir } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const AI = join(root, '.ai');
const CHECK = process.argv.includes('--check');

/** 根部入口：默认引用存根（指向 .ai/RULES.md）；--links 模式为符号链接。
 *  atImport：该工具官方支持 `@path` 导入语法（Claude Code / CodeBuddy 记忆机制），
 *  入口存根用 `@.ai/RULES.md` 引用而非纯文本指针；CodeBuddy 额外去重（不重复铁律节选，见下方循环）。 */
const LINK_TARGETS = [
  { tool: 'OpenAI Codex、智谱 ZCode 及所有遵循 AGENTS.md 标准的工具', file: 'AGENTS.md' },
  { tool: 'Anthropic Claude Code', file: 'CLAUDE.md', atImport: true },
  { tool: 'Google Gemini CLI / Antigravity', file: 'GEMINI.md' },
  { tool: '腾讯 CodeBuddy', file: 'CODEBUDDY.md', atImport: true, atImportDedupe: true },
];
const LINK_TO = '.ai/RULES.md';

/** 规则入口存根（需专属格式 / frontmatter / 长度考虑，不做链接） */
const STUB_TARGETS = [
  // Copilot 指令有长度限制：存根极小（引用 .ai/RULES.md），反而规避了截断问题
  { tool: 'GitHub Copilot', file: '.github/copilot-instructions.md' },
  {
    // Trae 规则靠 frontmatter 决定激活方式：alwaysApply: true 才每次会话常驻加载（官方四类生效方式）。
    // Trae 规则仅自然语言、不解析链接/导入（与 Claude 的 @ 导入不同）；根 AGENTS.md 默认不加载，
    // 需在 设置 > 规则 > 导入设置 手动开启「将 AGENTS.md 包含在上下文中」。本存根为纯指针，不重复
    // 入口存根的铁律节选（开启 AGENTS.md 时避免双份加载；未开启时靠本指针引导 AI 主动读 .ai/RULES.md）。
    tool: '字节 Trae',
    file: '.trae/rules/project_rules.md',
    frontmatter: ['---', 'description: ACME 项目规范入口（自动生成，引用 .ai/RULES.md）', 'alwaysApply: true', '---'],
    stub: `本仓库 AI 规范唯一完整副本是 \`.ai/RULES.md\`——开始任何任务前必须先完整阅读（Trae 规则不解析链接，请主动读取该文件）。
入口存根（铁律节选与蜂群流程开关）见根目录 \`AGENTS.md\`（需在 设置 > 规则 > 导入设置 中开启「将 AGENTS.md 包含在上下文中」才生效）。
AI 配置修改一律走 \`.ai/\` 源 + \`node .ai/sync.mjs\`（五步流程：\`.ai-memory/playbooks/ai-config.md\`）。`,
  },
  {
    // Qoder 依靠 frontmatter 决定规则激活方式：alwaysApply: true 才会每次会话常驻加载。
    // Qoder 同时自动读取根 AGENTS.md（冲突时 rules 优先）→ 本存根为纯指针，不重复入口存根的
    // 铁律节选，避免双份加载重复声明；rules 不解析链接，指针靠 Agent 任务内主动读源（ADR 2026-11-17-02）。
    tool: '阿里 Qoder',
    file: '.qoder/rules/project_rules.md',
    frontmatter: ['---', 'description: ACME 项目规范入口（自动生成，引用 .ai/RULES.md）', 'alwaysApply: true', '---'],
    stub: `本仓库 AI 规范唯一完整副本是 \`.ai/RULES.md\`——开始任何任务前必须先完整阅读（Qoder 规则不解析链接，请主动读取该文件）。
入口存根（铁律节选与蜂群流程开关）见根目录 \`AGENTS.md\`，Qoder 会自动读取；两者冲突时以本规则为准，本文件不重复其内容。
AI 配置修改一律走 \`.ai/\` 源 + \`node .ai/sync.mjs\`（五步流程：\`.ai-memory/playbooks/ai-config.md\`）。`,
  },
  {
    // Cursor 同时自动读取根/嵌套 AGENTS.md 与 `.cursor/rules/*.mdc`（官方四类生效方式；规则内
    // `@` 提及不会内联文件内容，与 Claude `@` 导入不同）。本存根为纯指针，不重复入口存根的铁律
    // 节选与多代理开关，避免双份加载重复声明（ADR 2026-11-18-01）。
    tool: 'Cursor',
    file: '.cursor/rules/project.mdc',
    frontmatter: ['---', 'description: ACME 项目规范入口（自动生成，引用 .ai/RULES.md）', 'alwaysApply: true', '---'],
    stub: `本仓库 AI 规范唯一完整副本是 \`.ai/RULES.md\`——开始任何任务前必须先完整阅读（Cursor 规则内 \`@\` 提及不会内联文件内容，请主动用工具读取该文件）。
入口存根（铁律节选与蜂群流程开关）见根目录 \`AGENTS.md\`，Cursor 会自动读取；分层规范见各目录 \`AGENTS.md\`（官方支持 nested，更具体者优先）。本文件不重复其内容。
跨工具公共池见 \`.cursor/agents|skills|commands/\`（引用存根 → \`.ai/\` 源）。
AI 配置修改一律走 \`.ai/\` 源 + \`node .ai/sync.mjs\`（五步流程：\`.ai-memory/playbooks/ai-config.md\`）。`,
  },
];

/** 跨工具公共池：.ai/<src>/** → 各工具目录（doc 仅作源目录说明，不派生）。
 *  targets 项为字符串或 { dir, transform }：transform 在派生 .md 时做目标工具的格式适配。 */
const POOLS = [
  // agents 源为蜂群文件夹（.ai/agents/<蜂群名>/<agent>.md），派生扁平到 <工具>/agents/<agent>.md，
  // 保持工具原生发现能力；溯源尾注仍保留完整源 rel（ADR 2026-11-26-01）。
  { src: 'agents', doc: 'AGENT-POOL.md', flat: true, targets: ['.claude/agents', { dir: '.gemini/agents', transform: transformGeminiAgent }, { dir: '.qoder/agents', transform: makePoolRefStub('agents') }, { dir: '.cursor/agents', transform: makePoolRefStub('agents', 'Cursor') }] },
  { src: 'skills', doc: 'SKILL-POOL.md', targets: ['.claude/skills', { dir: '.qoder/skills', transform: makePoolRefStub('skills') }, { dir: '.cursor/skills', transform: makePoolRefStub('skills', 'Cursor') }] },
  { src: 'commands', doc: 'COMMAND-POOL.md', targets: ['.claude/commands', { dir: '.qoder/commands', transform: makePoolRefStub('commands') }, { dir: '.cursor/commands', transform: makePoolRefStub('commands', 'Cursor') }] },
];

/** 工具专属配置：.ai/tools/<key>/** → <target>/**（doc 派生为工具目录内的说明文件） */
const TOOLS = [
  { key: 'claude', doc: 'CLAUDE-CODE.md', target: '.claude' },
  { key: 'cursor', doc: 'CURSOR.md', target: '.cursor' },
  { key: 'gemini', doc: 'GEMINI-CLI.md', target: '.gemini' },
  {
    // Trae：公共池派生为引用存根（frontmatter 保留供官方注册，正文指针指向 .ai/ 池源，磁盘零重复）。
    // agents 需额外适配 frontmatter：tools 数组→逗号分隔字符串、移除 model: inherit（Trae 缺省即继承
    // 主 Agent 模型）。详见 ADR 2026-11-17-04。
    key: 'trae',
    doc: 'TRAE.md',
    target: '.trae',
    mirrorPools: [
      { src: 'agents', transform: makeTraeAgentStub, flat: true },
      { src: 'skills', transform: makePoolRefStub('skills', 'Trae') },
      { src: 'commands', transform: makePoolRefStub('commands', 'Trae') },
    ],
  },
  { key: 'qoder', doc: 'QODER.md', target: '.qoder' },
  { key: 'github', doc: 'GITHUB-CONFIG.md', target: '.github' },
  // ZCode 的 agents/commands 引用存根由 .ai/agents/、.ai/commands/ 公共池派生（正文指针指向唯一源，
  // frontmatter 供 ZCode 注册）；不再保留手写镜像源，新增角色/命令自动纳入派生。
  // .zcode/ 本身 git 忽略、不入库，故标记 localOnly：--check 对缺失不判错（全新检出/CI 无该目录），存在必须一致（防漂移）。
  {
    key: 'zcode',
    doc: 'ZCODE.md',
    target: '.zcode',
    localOnly: true,
    mirrorPools: [
      { src: 'agents', transform: makeZcodeAgentStub, flat: true },
      { src: 'commands', transform: makeZcodeCommandStub },
    ],
  },
  // CodeBuddy：用户本机工具，.codebuddy/ git 忽略、不入库（官方建议项目 .codebuddy/ 提交共享，但本目录
  // 与 teams/ 等本机运行数据混居、CI 全新检出无此目录 → 维持 localOnly 口径，差异记录见 ADR 2026-11-17-03）。
  // 公共池 agents/skills/commands 派生为引用存根（官方原生识别 frontmatter，正文指针指向 .ai/ 池源，
  // 能用引用则用引用；取代 2026-11-17-01 的全量镜像，ADR 2026-11-17-03）。
  {
    key: 'codebuddy',
    doc: 'CODEBUDDY-CLI.md',
    target: '.codebuddy',
    localOnly: true,
    mirrorPools: [
      { src: 'agents', transform: makePoolRefStub('agents', 'CodeBuddy'), flat: true },
      { src: 'skills', transform: makePoolRefStub('skills', 'CodeBuddy') },
      { src: 'commands', transform: makePoolRefStub('commands', 'CodeBuddy') },
    ],
  },
];

/** 本机派生目标（git 忽略、不入库）前缀：--check 缺失不判错；sync 时照常写本机。 */
const LOCAL_ONLY_PREFIXES = TOOLS.filter((t) => t.localOnly).map((t) => `${t.target}/`);
const isLocalOnly = (rel) => LOCAL_ONLY_PREFIXES.some((p) => rel.startsWith(p));

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

async function statIfExists(abs) {
  try {
    return await lstat(abs);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

/** 逐级 lstat，不跟随源或目标的父路径链接；仅入口允许替换末级链接。 */
async function inspectPath(abs, { directory = false, allowLink = false } = {}) {
  const rel = relative(root, abs);
  if (isAbsolute(rel) || rel === '..' || rel.startsWith(`..${sep}`)) {
    throw new Error(`拒绝仓库外路径：${abs}`);
  }
  const parts = rel.split(sep).filter(Boolean);
  if (parts.length === 0) {
    // 仓库根本身：逐级循环不执行会返回 null，这里显式校验（孤儿检测的非递归根扫描依赖它）
    const st = await statIfExists(abs);
    if (!st || st.isSymbolicLink()) return null;
    if (!st.isDirectory()) throw new Error(`路径不是目录：.`);
    return st;
  }
  let current = root;
  for (let i = 0; i < parts.length; i++) {
    current = join(current, parts[i]);
    const st = await statIfExists(current);
    if (!st) return null;
    const leaf = i === parts.length - 1;
    if (st.isSymbolicLink()) {
      if (leaf && allowLink) return st;
      throw new Error(`拒绝符号链接路径：${normLink(relative(root, current))}（访问 ${normLink(rel)}）`);
    }
    if (!leaf || directory) {
      if (!st.isDirectory()) throw new Error(`路径不是目录：${normLink(relative(root, current))}`);
    } else if (!st.isFile()) {
      throw new Error(`拒绝非普通文件目标（目录不会删除）：${normLink(rel)}`);
    }
    if (leaf) return st;
  }
  return null;
}

async function walk(dir, { source = true, shallow = false } = {}) {
  const st = await inspectPath(dir, { directory: true, allowLink: !source });
  if (!st || st.isSymbolicLink()) return [];
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw error;
  }
  const files = [];
  for (const e of entries) {
    const p = join(dir, e.name);
    if (e.isSymbolicLink()) {
      if (source) throw new Error(`拒绝符号链接源：${normLink(relative(root, p))}`);
      continue;
    }
    if (e.isDirectory()) {
      if (shallow) continue;
      files.push(...(await walk(p, { source, shallow })));
    } else if (e.isFile()) files.push(p);
  }
  return files;
}

// ---- 0. 链接探针仅用于同步，且只在仓库 .ai-tmp/ 的独占临时目录内创建 ----
async function canSymlink() {
  const temp = join(root, '.ai-tmp');
  await inspectPath(temp, { directory: true });
  await mkdir(temp, { recursive: true });
  const probeDir = await mkdtemp(join(temp, 'sync-link-probe-'));
  const probe = join(probeDir, 'link');
  let created = false;
  try {
    await symlink('probe-target', probe);
    created = true;
    return true;
  } catch (error) {
    if (['EPERM', 'EACCES', 'ENOSYS', 'ENOTSUP', 'EOPNOTSUPP'].includes(error.code)) return false;
    throw error;
  } finally {
    if (created) await unlink(probe);
    await rmdir(probeDir);
  }
}
// ---- 0b. 行为/引擎开关 + 环境上下文：.ai/ai-coding-config.yml（源，sync 读取，不派生；缺省即当前行为） ----
const CONFIG_FILE = 'ai-coding-config.yml';
const CONFIG_DEFAULTS = {
  version: 1,
  engine: { entryMode: 'stub', orphanDetection: true, rulesBudgetBytes: 8192 },
  environment: { file: 'local.yaml' },
};
/** engine 固定键：其余键必须是 .ai/agents/ 下的蜂群名（见 listSwarmNames / validateConfig）。 */
const FIXED_ENGINE_KEYS = new Set(['entryMode', 'orphanDetection', 'rulesBudgetBytes']);
/** 蜂群触发模式档位（auto=功能开发类；always=所有产出业务代码的任务；never=停用；
 *  manual=仅用户对话显式要求时调用，无自动触发、无命令入口，如 code-review 评审蜂群）。 */
const SWARM_TRIGGER_MODES = ['auto', 'always', 'never', 'manual'];

/** 极简 YAML 子集解析：仅两层标量映射 + 注释；拒绝数组/Tab/多行/锚点等未支持语法（无外部依赖）。 */
function parseConfigYaml(text) {
  const rootObj = {};
  const stack = [{ indent: -1, obj: rootObj }];
  for (const raw of text.split('\n')) {
    if (!raw.trim() || raw.trimStart().startsWith('#')) continue;
    if (raw.includes('\t')) throw new Error(`${CONFIG_FILE}：不支持 Tab 缩进`);
    const hash = raw.indexOf(' #');
    const line = (hash === -1 ? raw : raw.slice(0, hash)).trimEnd();
    if (!line.trim()) continue;
    const indent = line.length - line.trimStart().length;
    const content = line.trim();
    if (content === '-' || content.startsWith('- ')) throw new Error(`${CONFIG_FILE}：不支持数组（${content}）`);
    const m = content.match(/^([A-Za-z0-9_-]+):(?:\s+(.*))?$/);
    if (!m) throw new Error(`${CONFIG_FILE}：无法解析行「${raw.trim()}」`);
    const val = m[2] === undefined ? '' : m[2].trim();
    while (stack.length > 1 && indent <= stack[stack.length - 1].indent) stack.pop();
    const parent = stack[stack.length - 1].obj;
    if (val === '') {
      const child = {};
      parent[m[1]] = child;
      stack.push({ indent, obj: child });
    } else {
      parent[m[1]] = parseConfigScalar(val, raw);
    }
  }
  return rootObj;
}

function parseConfigScalar(v, raw) {
  if (v === 'true') return true;
  if (v === 'false') return false;
  if (/^-?\d+$/.test(v)) return Number(v);
  if (v.length >= 2 && ((v[0] === '"' && v.endsWith('"')) || (v[0] === "'" && v.endsWith("'")))) return v.slice(1, -1);
  if (/^[|>&*!]/.test(v)) throw new Error(`${CONFIG_FILE}：不支持的 YAML 语法「${raw.trim()}」`);
  return v;
}

/** 蜂群名集合 = .ai/agents/ 下的子目录名（每个子目录 = 一个蜂群；fixture 无目录时为空集）。
 *  两条布局不变量在此强制（先于配置加载/校验，天然早于 config 校验）：
 *  1) 根级只允许蜂群目录与唯一说明文件 AGENT-POOL.md；
 *  2) 每个蜂群目录必须有普通文件 <蜂群名>-leader.md（符号链接/目录均拒绝），
 *     保证触发段引用的 leader 路径一定存在（ADR 2026-11-26-01）。 */
async function listSwarmNames() {
  const agentsDir = join(AI, 'agents');
  if (!(await inspectPath(agentsDir, { directory: true }))) return [];
  const entries = await readdir(agentsDir, { withFileTypes: true });
  const swarms = [];
  for (const e of entries) {
    if (e.isDirectory()) {
      swarms.push(e.name);
      continue;
    }
    if (e.isFile() && e.name === 'AGENT-POOL.md') continue;
    throw new Error(
      `.ai/agents/${e.name}：根级只允许蜂群目录与唯一说明文件 AGENT-POOL.md；请将该文件移入对应蜂群文件夹（如 .ai/agents/<蜂群名>/${e.name}）后重跑 node .ai/sync.mjs。`
    );
  }
  swarms.sort();
  for (const name of swarms) {
    const leaderRel = `.ai/agents/${name}/${name}-leader.md`;
    if (!(await inspectPath(join(agentsDir, name, `${name}-leader.md`)))) {
      throw new Error(
        `蜂群 ${name} 缺少 leader：${leaderRel} 不存在；请按 <蜂群名>-leader.md 约定补齐后重跑 node .ai/sync.mjs。`
      );
    }
  }
  return swarms;
}

/** 严格校验：未知键/错误类型/非法枚举一律报错，避免静默误配；与缺省合并。
 *  engine 除固定键外，仅允许键名属于传入的蜂群集合，取值必须是 auto|always|never|manual。 */
function validateConfig(parsed, swarmNames) {
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error(`${CONFIG_FILE}：顶层必须是映射`);
  const swarms = new Set(swarmNames ?? []);
  const cfg = structuredClone(CONFIG_DEFAULTS);
  for (const k of Object.keys(parsed)) {
    if (k !== 'version' && k !== 'engine' && k !== 'environment') throw new Error(`${CONFIG_FILE}：未知顶层键「${k}」`);
  }
  if ('version' in parsed && parsed.version !== 1) throw new Error(`${CONFIG_FILE}：version 必须为 1`);
  if ('engine' in parsed) {
    const e = parsed.engine;
    if (e === null || typeof e !== 'object' || Array.isArray(e)) throw new Error(`${CONFIG_FILE}：engine 必须是映射`);
    for (const [k, v] of Object.entries(e)) {
      if (FIXED_ENGINE_KEYS.has(k)) {
        if (k === 'entryMode' && v !== 'stub' && v !== 'symlink') throw new Error(`${CONFIG_FILE}：entryMode 必须为 stub|symlink`);
        if (k === 'orphanDetection' && typeof v !== 'boolean') throw new Error(`${CONFIG_FILE}：orphanDetection 必须为布尔`);
        if (k === 'rulesBudgetBytes' && (!Number.isInteger(v) || v <= 0)) throw new Error(`${CONFIG_FILE}：rulesBudgetBytes 必须为正整数`);
      } else if (swarms.has(k)) {
        if (!SWARM_TRIGGER_MODES.includes(v)) throw new Error(`${CONFIG_FILE}：${k} 必须为 auto|always|never|manual`);
      } else {
        throw new Error(`${CONFIG_FILE}：未知 engine 键「${k}」`);
      }
      cfg.engine[k] = v;
    }
  }
  // environment 是当前运行环境定义文件的指针：引擎只校验 schema 不消费（AI 读所指 .ai/env/ 文件）；不查文件存在性（fixture 为最小仓库）。
  if ('environment' in parsed) {
    const env = parsed.environment;
    if (env === null || typeof env !== 'object' || Array.isArray(env)) throw new Error(`${CONFIG_FILE}：environment 必须是映射`);
    for (const [k, v] of Object.entries(env)) {
      if (!(k in CONFIG_DEFAULTS.environment)) throw new Error(`${CONFIG_FILE}：未知 environment 键「${k}」`);
      if (k === 'file' && (typeof v !== 'string' || !/^[a-z0-9-]+\.yaml$/.test(v))) throw new Error(`${CONFIG_FILE}：file 必须为 .ai/env/ 下的 .yaml 文件名（小写字母/数字/连字符）`);
      cfg.environment[k] = v;
    }
  }
  return cfg;
}

async function loadConfig(swarmNames) {
  const p = join(AI, CONFIG_FILE);
  const st = await inspectPath(p); // 缺失→null（用缺省）；符号链接/非普通文件→抛错（源安全）
  if (!st) return structuredClone(CONFIG_DEFAULTS);
  const cfg = validateConfig(parseConfigYaml(norm(await readFile(p, 'utf8'))), swarmNames);
  // 折中存在性校验（审查 2026-11-14）：真实仓库必有 .ai/env/，目录在位时校验指针指向的文件存在，
  // 拼写错误当场报错而非调试任务当天才暴露；fixture 最小仓库无 env 目录，跳过（fixture 兼容性不变）。
  if (await inspectPath(join(AI, 'env'), { directory: true })) {
    if (!(await inspectPath(join(AI, 'env', cfg.environment.file)))) {
      throw new Error(`${CONFIG_FILE}：environment.file 指向的 ${cfg.environment.file} 不在 .ai/env/ 下`);
    }
  }
  return cfg;
}

const config = await loadConfig(await listSwarmNames());
const RULES_BUDGET = config.engine.rulesBudgetBytes;
const LINK_FLAG = process.argv.includes('--links') || process.env.AI_SYNC_LINK === '1';
/** 链接模式：CLI --links / AI_SYNC_LINK=1 优先；否则取配置 entryMode（缺省 stub）。 */
const USE_LINKS = LINK_FLAG || config.engine.entryMode === 'symlink';

// ---- 0c. AI 路径单一清单一致性（ADR 2026-11-17-01）：.ai/hooks/ai-paths.list 是唯一事实源，
//      pre-commit 与 mirror-check.mjs 直接消费；.ai/ci/ci.yml 的 mirror-sync 作业因 Actions YAML
//      难以共享文件常量而保留内联副本，此处强制其与本清单逐条（含顺序）一致，不一致即失败。
//      真实仓库两件套恒在；最小 fixture（两者皆缺）跳过，单缺即报错（结构性缺失）。
const AI_PATHS_LIST = '.ai/hooks/ai-paths.list';
function parseAiPathsList(text) {
  const paths = text.split('\n').map((l) => l.replace(/\r$/, '').trim()).filter((l) => l && !l.startsWith('#'));
  if (!paths.length) throw new Error(`${AI_PATHS_LIST} 无有效条目（每行一条，# 开头为注释）`);
  return paths;
}
/** 提取 ci.yml 中 AI_PATHS=( ...) 内联清单：按空白拆 token、去 shell 引号，保留顺序。 */
function extractCiInlineAiPaths(ciText) {
  const m = ciText.match(/AI_PATHS=\(\n([\s\S]*?)\n[ \t]*\)/);
  if (!m) throw new Error('.ai/ci/ci.yml 缺少 mirror-sync 作业的 AI_PATHS=(...) 内联清单（应与 .ai/hooks/ai-paths.list 逐条一致）');
  return m[1]
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'))
    .join('\n')
    .split(/\s+/)
    .map((t) => (t.startsWith("'") && t.endsWith("'") ? t.slice(1, -1) : t));
}
{
  const hasList = await inspectPath(join(AI, 'hooks', 'ai-paths.list'));
  const hasCi = await inspectPath(join(AI, 'ci', 'ci.yml'));
  if (hasList || hasCi) {
    if (!hasList) throw new Error('.ai/ci/ci.yml 在位但缺少 .ai/hooks/ai-paths.list（AI 路径单一清单）');
    if (!hasCi) throw new Error('.ai/hooks/ai-paths.list 在位但缺少 .ai/ci/ci.yml（镜像内联清单无法校验）');
    const list = parseAiPathsList(norm(await readFile(join(AI, 'hooks', 'ai-paths.list'), 'utf8')));
    const inline = extractCiInlineAiPaths(norm(await readFile(join(AI, 'ci', 'ci.yml'), 'utf8')));
    if (inline.join('\n') !== list.join('\n')) {
      throw new Error(
        `.ai/ci/ci.yml 的 AI_PATHS 内联清单与 ${AI_PATHS_LIST} 不一致（唯一事实源为后者）：\n` +
          `  清单：${list.join(' ')}\n  内联：${inline.join(' ')}\n` +
          '修复：按 .ai/hooks/ai-paths.list 同步 ci.yml 内联清单后重跑。'
      );
    }
  }
}

/** 期望集：根相对路径（正斜杠）→ 规范化内容 */
const expected = new Map();
/** 链接集：根相对路径 → { target, fallback }（symlink 可用时用链接，否则写 fallback 内容） */
const links = new Map();
/** 两类目标共用登记簿；所有平台均拒绝 Windows 下会重合的大小写变体。 */
const destinations = new Map();
function register(collection, rel, value, source) {
  const key = normLink(relative(root, join(root, rel))).toLowerCase();
  const previous = destinations.get(key);
  if (previous) {
    throw new Error(
      `派生目标冲突：${previous.source} → ${previous.rel}；${source} → ${rel}（含大小写冲突）`
    );
  }
  destinations.set(key, { rel, source });
  collection.set(rel, value);
}

// ---- 1. 公共规范：拼接为唯一完整副本 .ai/RULES.md ----
let body = '';
for (const f of (await walk(join(AI, 'rules'))).filter((f) => f.endsWith('.md')).sort()) {
  body += norm(await readFile(f, 'utf8')).trim() + '\n\n';
}
if (!body.trim()) {
  console.error('❌ .ai/rules/ 下没有任何规则源文件，中止。');
  process.exit(1);
}
const combinedBanner =
  '<!-- ⚠️ 本文件由 .ai/sync.mjs 从 .ai/rules/*.md 自动拼接生成，请勿直接修改；修改请编辑 .ai/rules/ 下的源文件并重新运行脚本。 -->';
const combined = norm(`${combinedBanner}\n\n# ACME 项目规范\n\n${body.trim()}\n`);
const ruleBytes = Buffer.byteLength(combined, 'utf8');
if (ruleBytes > RULES_BUDGET) {
  throw new Error(`.ai/rules/*.md → .ai/RULES.md 超出预算：${ruleBytes} bytes > ${RULES_BUDGET} bytes，未写入任何产物。`);
}
register(expected, '.ai/RULES.md', combined, '.ai/rules/*.md');

// ---- 2. 根部入口：默认引用存根（Claude Code 的存根用 @import 自动内联全文） ----
const stub = `本仓库所有 AI 规范统一维护于 \`.ai/\`。**开始任何任务前，必须先完整阅读 \`.ai/RULES.md\`** —— 它是公共规范（AI 协作准则、编码规范、Git 工作流、项目上下文）的唯一完整副本。

## 铁律（节选，完整版见 .ai/RULES.md）

1. 所有 AI 配置的唯一事实源是 \`.ai/\`，禁止直接修改任何工具目录下的派生文件。
2. 修改 \`.ai/\` 后必须立即运行 \`node .ai/sync.mjs\` 派生到所有工具，并通过 \`node .ai/sync.mjs --check\` 校验，否则提交会被 pre-commit 钩子与 CI 拒绝。
3. 未读 \`.ai/RULES.md\` 前，不得开始任何编码任务。
4. 凡修改 AI-Coding 配置（规范 / 分层规则 / 公共池 / 工具配置 / 钩子 / CI）：先读 \`.ai-memory/playbooks/ai-config.md\`，按其五步流程执行。`;

/** 蜂群触发段：对 engine 中每个蜂群键按档位生成一条（编号从 5 递增），注入所有入口存根。
 *  蜂群键缺省 → 不生成任何条目（等价 never 停用）；存根不占 RULES.md 预算；档位语义见 ARCHITECTURE「行为/引擎开关」。
 *  manual 档不引用四阶段流程 / 手册 / 命令入口：只在用户显式要求时调用，产物与流程口径见该蜂群说明文件。 */
function buildSwarmTrigger(engine) {
  const swarms = Object.keys(engine).filter((k) => !FIXED_ENGINE_KEYS.has(k)).sort();
  return swarms
    .map((name, i) => {
      const mode = engine[name];
      const head = `${5 + i}. 蜂群流程开关（engine.${name}: ${mode}）`;
      // 命名约定 <蜂群名>-leader.md，未来多蜂群避免重名（ADR 2026-11-26-01）。
      const refs = `leader \`.ai/agents/${name}/${name}-leader.md\`，手册 \`.ai-memory/playbooks/multi-agent.md\`，命令 \`/req\``;
      // 用途说明只登记在 .ai/agents/AGENT-POOL.md；触发段不写蜂群专属用途，避免新增蜂群时误标。
      if (mode === 'auto') return `${head}：蜂群 ${name} 的功能开发类任务自动按其四阶段契约流程执行——${refs}。`;
      if (mode === 'always') return `${head}：凡会产生业务代码的开发任务一律按蜂群 ${name} 的四阶段契约流程执行——${refs}。`;
      if (mode === 'manual') return `${head}：蜂群 ${name} 仅在用户对话显式要求时调用，无自动触发、无命令入口；产物与流程口径见该蜂群说明文件。`;
      return `${head}：蜂群 ${name} 的四阶段契约流程已停用，开发任务按常规方式执行——${refs}，勿主动调用。`;
    })
    .join('\n');
}
const entryTrigger = buildSwarmTrigger(config.engine);
const entryStub = `${stub}${entryTrigger ? `\n${entryTrigger}` : ''}`;

for (const t of LINK_TARGETS) {
  // @import 工具：官方记忆机制解析 `@path` 导入（Claude Code；CodeBuddy 同语法，官方文档明示支持
  // 相对/绝对路径、递归导入）。CodeBuddy 额外去重：@.ai/RULES.md 已内联完整规范，入口不再重复
  // 铁律节选，仅保留一句自然语言兜底指针与引擎注入的蜂群流程开关（后者不属于 RULES.md，无重复）。
  const imp = t.atImport ? '@.ai/RULES.md\n\n' : '';
  const body = t.atImportDedupe
    ? `${imp}# ACME 项目规范（入口）\n\n开始任何任务前，必须先完整阅读 \`.ai/RULES.md\`（上方 \`@\` 导入已自动内联其全文，本文件不重复其内容）。${entryTrigger ? `\n\n${entryTrigger}\n` : '\n'}`
    : `${imp}# ACME 项目规范（入口）\n\n${entryStub}\n`;
  register(links, t.file, {
    target: LINK_TO,
    fallback: norm(`${banner(t.tool)}\n\n${body}`),
  }, '.ai/rules/*.md（公共入口）');
}

// ---- 3. 需要专属格式的规则入口：生成存根 ----
for (const t of STUB_TARGETS) {
  const front = t.frontmatter ? t.frontmatter.join('\n') + '\n\n' : '';
  register(expected, t.file, norm(`${front}${banner(t.tool)}\n\n# ACME 项目规范（入口）\n\n${t.stub ?? entryStub}\n`),
    '.ai/rules/*.md（公共入口）');
}

// ---- 4. 分层 AGENTS.md：.ai/scopes/** → 仓库同路径 + 同级 CLAUDE.md ----
const SCOPES = join(AI, 'scopes');
for (const f of await walk(SCOPES)) {
  const rel = relative(SCOPES, f).split('\\').join('/');
  if (rel === 'SCOPED-RULES.md') continue;
  let content = norm(await readFile(f, 'utf8'));
  if (rel.endsWith('.md')) content = content.trimEnd() + footer(`.ai/scopes/${rel}`);
  register(expected, rel, content, `.ai/scopes/${rel}`);
  if (/(^|\/)AGENTS\.md$/.test(rel)) {
    // 同级 CLAUDE.md：默认为 @ 导入存根（引用同目录 AGENTS.md）；--links 模式为符号链接
    register(links, rel.replace(/AGENTS\.md$/, 'CLAUDE.md'), {
      target: 'AGENTS.md',
      fallback: norm(
        `@AGENTS.md\n\n本目录的目录级规范在同目录 \`AGENTS.md\`（唯一事实源：\`.ai/scopes/${rel}\`）。\n`
      ),
    }, `.ai/scopes/${rel}（同级 CLAUDE.md 入口）`);
  }
}

// ---- 5. 公共池、工具专属、其他目标（逐文件派生） ----
/** Gemini CLI 内置工具名映射：公共池 frontmatter 统一用 Claude Code 工具名（单一源），
 *  派生到 .gemini/agents 时转 Gemini 内置名（ADR 2026-11-14-24）；仅作用于 frontmatter，正文不动。 */
const GEMINI_TOOL_MAP = {
  Read: 'read_file',
  Write: 'write_file',
  Edit: 'replace',
  Grep: 'grep_search',
  Glob: 'glob',
  Bash: 'run_shell_command',
};
function transformGeminiAgent(content) {
  const m = content.match(/^---\r?\n([\s\S]*?)\r?\n---/);
  if (!m) return content;
  const front = m[1]
    .replace(
      /^tools:\s*\[([^\]]*)\]\s*$/m,
      (_, list) =>
        `tools: [${list
          .split(',')
          .map((s) => s.trim())
          .map((n) => GEMINI_TOOL_MAP[n] || n)
          .join(', ')}]`,
    )
    .replace(/^model:\s*inherit\s*$/m, '');
  return `${content.slice(0, m.index)}---${front.replace(/\n+$/, '\n')}---${content.slice(m.index + m[0].length)}`;
}

/** 公共池引用存根（ADR 2026-11-17-02 Qoder / 2026-11-17-03 CodeBuddy）：frontmatter 原样保留供工具
 *  注册（name/description/tools/model 属注册元数据，非正文声明）；正文仅指针指向 .ai/ 池源，
 *  磁盘零重复——与 .zcode/ 手写存根同口径。官方项目级目录：
 *  Qoder `.qoder/{agents,skills,commands}/`、CodeBuddy `.codebuddy/{agents,skills,commands}/`。
 *  无 frontmatter 的源按路径合成最小注册头（fixture 兼容）。 */
function makePoolRefStub(pool, tool = 'Qoder') {
  return (content, rel) => {
    const m = content.match(/^---\n[\s\S]*?\n---/);
    const name = (pool === 'skills' ? rel.split('/')[0] : rel.replace(/\.md$/, '').split('/').pop()).toLowerCase();
    const front = m ? m[0] : `---\nname: ${name}\ndescription: 引用存根（完整内容见 .ai/${pool}/${rel}）\n---`;
    return `${front}\n\n本文件为 ${tool} 引用存根：完整内容唯一源 \`.ai/${pool}/${rel}\`。\n\n执行前先完整读取该源文件并严格遵循；源内相对路径（scripts/、templates/ 等）相对 \`.ai/${pool}/\` 下对应目录解析。本存根不含正文，禁止在此手改内容。\n`;
  };
}

/** Trae Subagent 引用存根（ADR 2026-11-17-04）：在 makePoolRefStub 口径基础上额外适配 frontmatter——
 *  Trae 官方 subagent frontmatter 要求 tools 为逗号分隔字符串（非数组）、model 不支持 inherit（缺省即
 *  继承主 Agent 模型）；name/description 原样保留供注册。正文仅指针指向 .ai/agents/ 源，磁盘零重复。 */
function makeTraeAgentStub(content, rel) {
  const m = content.match(/^---\n([\s\S]*?)\n---/);
  const name = rel.replace(/\.md$/, '').split('/').pop().toLowerCase();
  let front;
  if (m) {
    const fm = m[1]
      .replace(
        /^tools:\s*\[([^\]]*)\]\s*$/m,
        (_, list) => `tools: ${list.split(',').map((s) => s.trim()).join(', ')}`,
      )
      .replace(/^model:\s*inherit\s*$/m, '');
    front = `---\n${fm.replace(/\n+$/, '\n')}---`;
  } else {
    front = `---\nname: ${name}\ndescription: 引用存根（完整内容见 .ai/agents/${rel}）\n---`;
  }
  return `${front}\n\n本文件为 Trae 引用存根：完整内容唯一源 \`.ai/agents/${rel}\`。\n\n执行前先完整读取该源文件并严格遵循；本存根不含正文，禁止在此手改内容。\n`;
}

/** ZCode Agent 注册存根：frontmatter 原样保留供 ZCode 注册，正文仅指针指向 .ai/agents/ 公共池源。
 *  取代 .ai/tools/zcode/agents/ 手写镜像——新增蜂群/角色自动纳入派生，杜绝漏配（ADR 2026-11-26-01）。
 *  无 frontmatter 的源按路径合成最小注册头（fixture 兼容）。rel 形如 ai-coding-dev/ai-coding-dev-leader.md。 */
function makeZcodeAgentStub(content, rel) {
  const m = content.match(/^---\n[\s\S]*?\n---/);
  const name = rel.replace(/\.md$/, '').split('/').pop().toLowerCase();
  const front = m ? m[0] : `---\nname: ${name}\ndescription: 引用存根（完整内容见 .ai/agents/${rel}）\n---`;
  return `${front}\n\n你的完整角色定义唯一源：\`.ai/agents/${rel}\`（公共池）。\n\n被 spawn 后第一步：立即完整读取上述文件并严格遵循。本文件仅为 ZCode 注册存根（frontmatter 供工具识别），不含角色内容。\n`;
}

/** ZCode Command 注册存根：frontmatter 原样保留（description 与 .ai/commands/ 源逐字一致），正文沿用
 *  现有 ZCode 命令存根口径（唯一源指针 + 立即读取执行提示 + $ARGUMENTS 占位）；rel 形如 req.md。 */
function makeZcodeCommandStub(content, rel) {
  const m = content.match(/^---\n[\s\S]*?\n---/);
  const name = rel.replace(/\.md$/, '').toLowerCase();
  const front = m ? m[0] : `---\ndescription: 命令 ${name}（完整内容见 .ai/commands/${rel}）\n---`;
  return `${front}\n\n完整命令定义唯一源：\`.ai/commands/${rel}\`。立即读取并按其执行（含 \`engine.ai-coding-dev\` 蜂群开关自检与四阶段流程）。\n\n用户参数：$ARGUMENTS\n`;
}

/** 逐文件派生。flat=true：输出路径取源文件 basename（蜂群子目录不落到工具目录，保持原生发现能力），
 *  但 label / footer / transform 仍使用完整源 rel，登记簿按输出路径检测重名冲突。 */
async function derive(srcDir, outRoot, srcLabel, { excludeDocs = [], placardDoc = null, transform = null, flat = false } = {}) {
  for (const f of await walk(srcDir)) {
    const rel = relative(srcDir, f).split('\\').join('/');
    if (excludeDocs.includes(rel)) continue;
    const label = `${srcLabel}${rel}`;
    const outRel = flat ? rel.split('/').pop() : rel;
    let content = norm(await readFile(f, 'utf8'));
    if (placardDoc && rel === placardDoc) content = PLACARD_HEAD + content;
    if (transform && rel.endsWith('.md')) content = transform(content, rel);
    if (rel.endsWith('.md')) content = content.trimEnd() + footer(label);
    else if (/\.(ya?ml|toml)$/.test(rel))
      content = `# 派生自 ${label}（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs\n\n` + content;
    else if (/\.(mjs|js|ts|tsx)$/.test(rel)) {
      // 脚本类派生物同样带标记：否则孤儿检测扫到它们时 DERIVED_MARK 匹配不上（源删后残留漏报）。
      // shebang 必须在第 1 行，否则 node --check 报 Invalid or unexpected token；标记插到 shebang 行之后。
      const mark = `// 派生自 ${label}（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs\n`;
      if (content.startsWith('#!')) {
        const nl = content.indexOf('\n');
        content = nl === -1
          ? `${content}\n${mark}`
          : content.slice(0, nl + 1) + mark + content.slice(nl + 1);
      } else {
        content = mark + content;
      }
    }
    register(expected, `${outRoot}/${outRel}`, content, label);
  }
}

for (const p of POOLS) {
  for (const t of p.targets) {
    const { dir, transform } = typeof t === 'string' ? { dir: t, transform: null } : t;
    await derive(join(AI, p.src), dir, `.ai/${p.src}/`, { excludeDocs: [p.doc], transform, flat: !!p.flat });
  }
}
for (const t of TOOLS) {
  await derive(join(AI, 'tools', t.key), t.target, `.ai/tools/${t.key}/`, { placardDoc: t.doc });
  // mirrorPools：把跨工具公共池派生进该工具目录（localOnly 本机目标）。条目为字符串（原样拷贝，
  // ADR 2026-11-17-01 zcode 口径）或 { src, transform }（引用存根等格式适配，ADR 2026-11-17-03）。
  // 池目录说明文件（AGENT-POOL.md 等）与 POOLS 循环同样排除；transform 仅作用于 .md（技能辅助
  // 文件照旧全量拷贝）。与 .ai/tools/<key>/ 同名同路径源会经 destinations 登记簿触发「派生目标冲突」。
  for (const pool of t.mirrorPools ?? []) {
    const { src: poolName, transform: poolTransform, flat: poolFlat } = typeof pool === 'string' ? { src: pool, transform: null, flat: false } : pool;
    const poolDef = POOLS.find((p) => p.src === poolName);
    await derive(join(AI, poolName), `${t.target}/${poolName}`, `.ai/${poolName}/`, {
      excludeDocs: poolDef ? [poolDef.doc] : [],
      transform: poolTransform,
      flat: !!poolFlat,
    });
  }
}
for (const e of EXTRA) {
  // CI-GUIDE.md 是目录说明，不派生到 .github/workflows/
  await derive(join(AI, e.src), e.target, `.ai/${e.src}/`, { excludeDocs: ['CI-GUIDE.md'] });
}

// ---- 6. 写入 / 校验：全部登记和路径预检通过后才允许写入（包括探针） ----
for (const rel of expected.keys()) await inspectPath(join(root, rel));
for (const rel of links.keys()) await inspectPath(join(root, rel), { allowLink: true });
const SYMLINK_OK = !CHECK && USE_LINKS ? await canSymlink() : false;
let errors = 0;
let skippedLocalOnly = 0;

async function ensureContent(rel, want) {
  const abs = join(root, rel);
  await inspectPath(abs);
  let cur = null;
  try {
    cur = norm(await readFile(abs, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (CHECK) {
    if (cur === null) {
      if (isLocalOnly(rel)) {
        // 本机派生目标不入库：全新检出/CI 必然缺失，缺失不判错（sync 时照常写本机）
        skippedLocalOnly++;
        console.log(`⏭️ ${rel}（本机派生目标缺失，跳过校验）`);
        return;
      }
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
  const st = await inspectPath(abs, { allowLink: true });

  if (CHECK) {
    // 链接（目标正确）或降级内容（匹配）任一通过
    if (st?.isSymbolicLink()) {
      const cur = normLink(await readlink(abs));
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
    const cur = st?.isSymbolicLink() ? normLink(await readlink(abs)) : null;
    if (cur === target) {
      console.log(`🔗 ${rel} → ${target}（已存在）`);
    } else {
      if (st) await unlink(abs);
      await mkdir(dirname(abs), { recursive: true });
      await symlink(target, abs);
      console.log(`🔗 已链接 ${rel} → ${target}`);
    }
  } else {
    // 默认模式：写引用存根（同时会把已有的链接替换为存根，修复 git 检出损坏的链接文本）
    if (st?.isSymbolicLink()) await unlink(abs);
    await ensureContent(rel, fallback);
  }
}

for (const [rel, want] of expected) {
  await ensureContent(rel, want);
}

// ---- 7. 孤儿派生文件检测：带派生标记、但已不在期望集内的残留（可经 orphanDetection 关闭） ----
// 扫描范围：TOOLS 目标目录（递归）+ scopes 派生目标的同级目录（非递归，防误扫用户文件）
// + 仓库根（非递归，覆盖根部入口残留）；只报带派生标记的文件，不影响用户自建内容。
if (config.engine.orphanDetection) {
  const scanDirs = new Map(); // dir → shallow?
  for (const t of TOOLS) scanDirs.set(join(root, t.target), false);
  for (const { rel, source } of destinations.values()) {
    if (source.startsWith('.ai/scopes/')) scanDirs.set(dirname(join(root, rel)), true);
  }
  scanDirs.set(root, true);
  for (const [dir, shallow] of scanDirs) {
    for (const f of await walk(dir, { source: false, shallow })) {
      const rel = relative(root, f).split('\\').join('/');
      if (destinations.has(rel.toLowerCase())) continue;
      let txt = '';
      try {
        txt = await readFile(f, 'utf8');
      } catch (error) {
        if (error.code === 'ENOENT') continue;
        throw error;
      }
      if (DERIVED_MARK.test(txt)) {
        errors++;
        console.error(`❌ 孤儿派生文件 ${rel}（源已删除？请手动移除）`);
      }
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
    ? `\n校验通过：${expected.size + links.size} 个派生文件/链接均与 .ai/ 同步${
        skippedLocalOnly ? `（其中本机未派生跳过 ${skippedLocalOnly} 项）` : ''
      }${config.engine.orphanDetection ? '，无孤儿残留' : '（孤儿检测已关闭）'}。`
    : `\n完成：${expected.size} 个文件已派生、${links.size} 个入口已处理（${
        USE_LINKS && SYMLINK_OK ? '符号链接模式（--links 或 entryMode=symlink）' : '引用存根模式（默认）'
      }）。`
);
