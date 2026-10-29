# 分工具使用教程

> 每个工具讲三件事：**读什么** / **如何确认生效（冒烟测试）** / **如何让它维护配置**。
> 通用流程见 [GUIDE.md](GUIDE.md)，提示词库见 [PROMPTS.md](PROMPTS.md)，体系说明见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 通用冒烟测试（任何工具都适用）

打开工具，在仓库根目录问它：

> 「当前项目的 AI 配置铁律第一条是什么？修改 AI 配置后必须运行什么命令？」

**正确回答**应包含：唯一事实源是 `.ai/`；必须运行 `node .ai/sync.mjs` 并通过 `--check`。答不出 = 该工具没读到规则，按各工具下方「读什么」排查入口文件是否存在。

---

## 1. Claude Code（Anthropic）

- **读什么**：`CLAUDE.md`（内含 `@.ai/RULES.md` 导入，加载时**自动内联规范全文**）；`.claude/` 下有派生的 `ai-config-editor` 子代理、`ai-config` 技能。
- **冒烟**：问通用测试句；或 `/agents`、`/skills` 列表中应能看到 `ai-config-editor` / `ai-config`。
- **维护配置**：直接说意图（如「把提交规范改成 72 字」），会自动走技能流程；复杂任务可说「用 ai-config-editor 子代理」。
- **注意**：个人私有设置放 `.claude/settings.local.json`（不入 `.ai/`，已被 gitignore）；团队共享的 `settings.json` 源在 `.ai/tools/claude/`。

## 2. Codex（OpenAI）

- **读什么**：根 `AGENTS.md`（引用存根）→ 按指引读 `.ai/RULES.md`。
- **冒烟**：问通用测试句。
- **维护配置**：说意图 + 补一句「按 `.ai-memory/playbooks/ai-config.md` 的流程执行」（Codex 无本仓库子代理，点名手册更稳）。

## 3. Cursor（Anysphere）

- **读什么**：`.cursor/rules/project.mdc`（`alwaysApply: true` 常驻加载；Cursor 也读根 `AGENTS.md`）。
- **冒烟**：Settings → Rules 中应能看到 `project.mdc`；或 Agent 模式问通用测试句。
- **维护配置**：同 Codex（说意图 + 点名手册）。
- **注意**：私有按文件类型生效的规则，源放 `.ai/tools/cursor/rules/*.mdc`（用 `globs` frontmatter）。

## 4. GitHub Copilot（GitHub/微软）

- **读什么**：`.github/copilot-instructions.md`（Chat / Agent 模式自动读取；存根内联了铁律节选）。
- **冒烟**：Copilot Chat 中问通用测试句。
- **维护配置**：同上。**注意**：行内补全（inline completion）不主动读文件——关键红线已在存根节选中兜底；按路径生效的指令源放 `.ai/tools/github/instructions/`。

## 5. Gemini CLI / Antigravity（Google）

- **读什么**：根 `GEMINI.md`（引用存根）→ `.ai/RULES.md`；`.gemini/agents/` 下有派生子代理。
- **冒烟**：问通用测试句；子代理列表应含 `ai-config-editor`。
- **维护配置**：说意图（可点名 `ai-config-editor`）。
- **注意**：Gemini 的自定义命令是 **TOML**——斜杠命令需在 `.ai/tools/gemini/commands/` 手工建 TOML 版（见 GUIDE 案例 5）。

## 6. CodeBuddy（腾讯）

- **读什么**：根 `CODEBUDDY.md`（引用存根；缺省时工具会自动回退读 `AGENTS.md`，双保险）。
- **冒烟**：Craft/Ask 模式问通用测试句。
- **维护配置**：说意图 + 点名手册。

## 7. Trae（字节跳动）

- **读什么**：`.trae/rules/project_rules.md`（Trae 自动加载 `rules/` 目录下全部 md，Builder/Agent 模式生效）。
- **冒烟**：Builder 模式问通用测试句。
- **维护配置**：说意图 + 点名手册。SOLO 模式等私有规则源放 `.ai/tools/trae/rules/`。

## 8. Qoder（阿里）

- **读什么**：`.qoder/rules/project_rules.md`（含 `alwaysApply: true`，每次会话常驻；Qoder 也读根 `AGENTS.md`）。
- **冒烟**：Quest/Agent 模式问通用测试句。
- **维护配置**：说意图 + 点名手册。
- **背景**：Qoder 靠 frontmatter 决定规则激活方式，无 frontmatter 的规则不会自动加载——这正是派生存根自动带 `alwaysApply` 的原因。

## 9. ZCode（智谱 Z.AI）

- **读什么**：根 `AGENTS.md`（官方确认的项目规则文件；**不读** `CLAUDE.md`，后者仅在首次导入时一次性迁移）。
- **冒烟**：问通用测试句。
- **维护配置**：说意图 + 点名手册。MCP 配置在 `.zcode/` 目录（ZCode 自管理）。

## 10. iFlow CLI（心流）

- **读什么**：根 `AGENTS.md` 与 `CLAUDE.md` 双入口（内容一致，任一读取方式都覆盖）；`.iflow/` 下有派生的 `agents/`、`commands/`、`workflows/`（承接技能）。
- **冒烟**：问通用测试句；`/` 命令列表应含派生的命令。
- **维护配置**：直接说意图，可点名 `ai-config-editor` 子代理。

## 11. AGENTS.md 标准族（零配置接入）

**Amp、Goose、OpenCode、Zed、Kimi Code、Amazon Q、Junie CLI、Jules 等云端/开源 Agent**：

- **读什么**：根 `AGENTS.md`——这些工具遵循 AGENTS.md 开放标准，**无需任何登记**，天然覆盖。
- **冒烟**：问通用测试句。
- **维护配置**：说意图 + 点名手册。

---

## 快速对照表

| 工具 | 入口 | 有子代理 | 有技能 | 维护提示词要加「按手册执行」？ |
|---|---|---|---|---|
| Claude Code | `CLAUDE.md` + `.claude/` | ✅ | ✅ | 不必（自动） |
| iFlow CLI | 双入口 + `.iflow/` | ✅ | ✅(workflow) | 不必 |
| Gemini CLI | `GEMINI.md` + `.gemini/` | ✅ | — | 可省 |
| Codex / Cursor / Copilot / Trae / Qoder / ZCode / CodeBuddy / 标准族 | 各自存根 / `AGENTS.md` | — | — | **建议加** |

## 排障

规则没生效？依次检查：① 入口文件存在且非手改（`node .ai/sync.mjs --check`）；② 工具是否在该仓库根目录启动；③ 该工具是否有「需在设置中启用项目规则」的开关；④ 仍不行 → 按工具官方文档核对入口路径是否变化（工具会演进，必要时更新 `sync.mjs` 的登记，见 [ARCHITECTURE.md](ARCHITECTURE.md)「新增一个 AI 工具的兼容」）。
