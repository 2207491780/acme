# Antigravity 派生机制对齐官方配置方案（`.agents/skills.json` 直引公共技能池）与引擎修复

日期：2026-11-27
背景关联：`2026-11-14-24-gemini-agent-tool-mapping.md`（Gemini CLI 子代理映射）、`2026-11-18-01-cursor-official-alignment.md`（Cursor 官方对齐与 `.agents/skills/` 兼容扫描说明）。

## 背景

1. **Antigravity 与 Gemini CLI 发现路径不同**：仓库此前把 Google Gemini CLI 与 Antigravity 合并为单一目标（`GEMINI.md` + `.gemini/`）。按 Antigravity 官方定制体系（`agy-customizations` 规范，2026-11-27 核实）：
   - **规则**：自动按当前工作目录向上发现 `AGENTS.md` 与 `GEMINI.md`（无 frontmatter，24KB/文件，按真实路径去重），并支持 `.agents/rules/*.md`。
   - **技能**：项目级工作区发现目录为 `.agents/`（或 `.agent/`），支持 `.agents/skills/<name>/SKILL.md` 及声明式清单 **`.agents/skills.json`**（`entries: [{ "path": "..." }]`，相对仓库根解析，单层扫描 `<name>/SKILL.md`）。Antigravity **不扫描** `.gemini/skills/`、`.gemini/agents/` 或 `.gemini/commands/*.toml`。
   - **Plugins / Hooks / MCP**：分别对应 `.agents/plugins/`、`.agents/hooks.json`、`.agents/mcp_config.json`。
   - **子代理**：使用内置子代理（`self` / `research` / `browser` / `image-generator`）与动态 `define_subagent`，非静态 `.gemini/agents/*.md`。
2. **现状缺口**：Antigravity 虽然已能加载根 `AGENTS.md` / `GEMINI.md` 与 3 份分层 `AGENTS.md`，但公共池的 3 个技能（`ai-config`、`team-leader`、`code-review`）完全无法被 Antigravity 自动发现。
3. **同期检出的引擎缺陷**：
   - `.ai/sync.mjs` 的 `transformGeminiAgent` 漏掉开头 `---` 后换行（全部 15 个 `.gemini/agents/*.md` 首行变成 `---name: ...`），且 `transformGeminiAgent` 与 `makeTraeAgentStub` 在缺少 `model: inherit` 时漏掉闭合 `---` 前换行、在 `model: inherit` 位于中间行时残留空行。
   - `.ai/sync.mjs` 拼接 `.ai/rules/*.md` 与 `walk()` 排序受 OS 路径分隔符（`\` vs `/`）和文件系统顺序影响；分层 `CLAUDE.md` 存根缺少 `DERIVED_MARK`（孤儿检测盲区）。
   - `.ai/worktree.mjs` 中 `ACME_WORKTREE_GC_MIN_AGE_MS` 非法值会产出 `NaN` 静默禁用 6h 宽限期、`remove` 未按 `norm(unquoted(...))` 匹配 porcelain 路径、分支留存校验用 `.includes()` 子串匹配而非精确 `branchExists()`；`.ai/hooks/pre-commit` 读 `ai-paths.list` 未剥离 Windows `\r`。

## 决定

1. **新增 Antigravity 工具目标（`.agents/`）**：在 `.ai/sync.mjs` 的 `TOOLS` 登记 `{ key: 'antigravity', doc: 'ANTIGRAVITY.md', target: '.agents' }`，源目录 `.ai/tools/antigravity/`：
   - `.ai/tools/antigravity/skills.json` → 派生到 `.agents/skills.json`，内容为 `{"entries":[{"path":".ai/skills"}]}`——使用 Antigravity 官方推荐的 VCS 共享清单直引公共技能池，磁盘零重复、直接保留技能内 `scripts/` 与 `templates/` 相对路径、未来新增技能自动生效。
   - `.ai/tools/antigravity/ANTIGRAVITY.md` → 派生为 `.agents/ANTIGRAVITY.md`（自动前置「生成产物」标头）。
2. **不重复派生 `.agents/rules/` 与 `.agents/skills/`**：
   - 根/分层 `AGENTS.md` + 根 `GEMINI.md` 已由 Antigravity 原生加载，再建 `.agents/rules/` 会导致三份注入。
   - 不建 `.agents/skills/` 副本/存根：既避免磁盘重复，又避免 Cursor 对 `.agents/skills/` 的兼容发现与 `.cursor/skills/` 产生双重扫描。
3. **纳入 AI 路径单一清单与镜像校验**：将 `.agents` 同步加入 `.ai/hooks/ai-paths.list` 与 `.ai/ci/ci.yml` 的 `AI_PATHS`（保证 pre-commit 暂存完整性与双分支镜像一致）。
4. **一并修复引擎缺陷并补齐回归测试**：修复 `sync.mjs`、`worktree.mjs`、`pre-commit` 上述全部问题，在 `sync.test.mjs`、`worktree.test.mjs`、`ai-paths.test.mjs` 锁定回归。

## 兼容矩阵（Antigravity 官方释义 vs 本仓库适配，2026-11-27 核实）

| 能力 | 官方支持情况 | 本仓库适配 |
|---|---|---|
| Rules（`AGENTS.md` / `GEMINI.md`） | ✅ 目录向上分层自动加载（无 frontmatter，24KB/文件，按路径去重） | 根 `AGENTS.md` + `GEMINI.md` + 3 份分层 `AGENTS.md` 已覆盖（保留 `GEMINI.md` 供 Gemini CLI，两份存根各 ~1KB 可接受） |
| Rules（`.agents/rules/*.md`） | ✅ 支持 `always_on` / `model_decision` | **不派生**——避免与根/分层 `AGENTS.md`、`GEMINI.md` 重复加载 |
| Skills（`.agents/skills.json` / `.agents/skills/`） | ✅ 渐进式披露（仅注入 name + description，按需读全文）；`skills.json` 支持指向仓库任意目录单层扫描 | **采用 `.agents/skills.json` 直引 `.ai/skills`**（零重复、保留相对脚本/模板链接、避免与 Cursor 冲突） |
| Subagents | ✅ 内置（`self`/`research`/`browser`/`image-generator`）+ 动态 `define_subagent`；不扫描 `.gemini/agents/` | 运行时读取 `.ai/agents/<蜂群名>/<role>.md` 唯一源配合委派；`.gemini/agents/` 继续服务 Gemini CLI |
| Commands / Workflows | ✅ 内置斜杠命令；不扫描 `.gemini/commands/*.toml` | 通过已发现技能（`team-leader` / `code-review` / `ai-config`）与自然语言触发，或直接读 `.ai/commands/` 源 |
| Hooks / MCP / Plugins | ✅ `.agents/hooks.json` / `.agents/mcp_config.json` / `.agents/plugins/` | **当前未配置**工具级源；需要时在 `.ai/tools/antigravity/` 建源派生 |

## 被否的选项

- **把 `.ai/skills/` 拷贝或引用存根派生到 `.agents/skills/`**：否决——Antigravity 官方已提供 `.agents/skills.json` 直引机制，拷贝/存根会徒增冗余文件，且会被 Cursor 的 `.agents/skills/` 兼容扫描重复发现。
- **删除根 `GEMINI.md` 仅留 `AGENTS.md` 以消除 Antigravity 双份存根**：否决——Gemini CLI 依赖根 `GEMINI.md` 作为入口（且回归测试锁定）；两份存根各 ~1KB 远低于 20k token 规则预算，保留两者可同时兼顾 Gemini CLI 与 Antigravity。
- **把 Antigravity 归入 `.gemini/` 派生 `.gemini/skills/`**：否决——Antigravity 项目级技能发现根是 `.agents/`（或 `.agent/`），不是 `.gemini/`，放 `.gemini/skills/` 在 Antigravity 内无效。
