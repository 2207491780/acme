# ACME AI 配置使用指南

> 面向使用者的操作手册，含完整实操案例与故障排查。体系结构说明见 [README.md](README.md)；项目介绍见[根 README.md](../README.md)。
> 本指南随 `.ai/` 一起维护（AI-Coding 相关内容的唯一事实源在本目录）。

## 准备工作（一次性）

```bash
git init && git config core.hooksPath .ai/hooks
# Linux/macOS 协作者首次提交前，给钩子加可执行位：
git add .ai/hooks/pre-commit && git update-index --chmod=+x .ai/hooks/pre-commit
```

## 通用四步流程（所有案例共用）

> 即「改 → 派生 → 校验 → 提交」的机械循环；AI 工具执行时遵循 [playbook 五步流程](../.ai-memory/playbooks/ai-config.md) = 定位意图 + 下述 ①②③ + 汇报三件套。

```bash
# ① 只修改 .ai/ 下的源文件
# ② 一键派生到所有工具
node .ai/sync.mjs
# ③ 校验（必须全绿）
node .ai/sync.mjs --check
# ④ 源 + 派生结果一起提交（pre-commit 会再次校验）
git add -A && git commit -m "chore(ai): xxx"
```

---

## 案例 1：修改全局规范（所有工具生效）

**需求**：把提交信息 subject 上限从 50 字改为 72 字。

```bash
# ① 编辑 .ai/rules/git-workflow.md，修改对应行：
#    - subject 用中文或英文祈使句，不超过 72 字，结尾不加句号
# ②③④ 通用流程
```

**结果**：`AGENTS.md`（Codex/ZCode/AGENTS.md 标准族）、`CLAUDE.md`（Claude Code，`@` 导入自动内联）、`GEMINI.md`、`CODEBUDDY.md`、`.cursor/.trae/.qoder` 规则、`.github/copilot-instructions.md` 全部同步——任何工具下次会话读到的都是新规范。

## 案例 2：给子目录加专属规则（分层，就近生效）

**需求**：`packages/api` 目录使用统一的错误处理约定。

```bash
mkdir -p .ai/scopes/packages/api
```

新建 `.ai/scopes/packages/api/AGENTS.md`：

```markdown
# packages/api 目录规范

- 错误统一抛出 `ApiError`，必须携带 `code` 字段。
- handler 内不写 try/catch，异常交给中间件统一处理。
```

跑通用流程后生成：

- `packages/api/AGENTS.md`（内容副本，带溯源尾注）
- `packages/api/CLAUDE.md`（`@AGENTS.md` 导入存根，Claude Code 就近生效；引擎只为 scope 的 AGENTS.md 生成**同级** CLAUDE.md）

遵循 AGENTS.md 标准的工具在该目录工作时会「从宽到具体」加载：根规范 + 本目录规则。

## 案例 3：新增跨工具子代理

**需求**：一个专职代码评审的 subagent。

新建 `.ai/agents/code-reviewer.md`：

```markdown
---
name: code-reviewer
description: 专职代码评审的子代理。产生代码改动后应主动使用。
tools: [Read, Grep, Glob]
model: inherit
---

你是资深代码评审员。关注：正确性、边界条件、测试覆盖、与 .ai/rules/coding-standards.md 的一致性。
输出：按严重度排序的问题清单，每条含文件:行号与修复建议。
```

跑通用流程后派生到 `.claude/agents/`、`.iflow/agents/`、`.gemini/agents/`。

**使用**：Claude Code 会按 description 自动委派；也可显式说「用 code-reviewer 审一下这次改动」。只给单一工具用时，改放 `.ai/tools/<工具>/agents/` 即可。

## 案例 4：新增跨工具技能

**需求**：发版前检查清单。

```bash
mkdir -p .ai/skills/release-checklist
```

新建 `.ai/skills/release-checklist/SKILL.md`：

```markdown
---
name: release-checklist
description: 发版前检查清单。执行发版或版本号变更任务时应主动使用。
---

1. 确认 version 文件与 CHANGELOG 一致
2. 运行全量测试并通过
3. 确认 .ai/rules/project.md 的构建命令可执行
4. 打 tag 并推送
```

跑通用流程后派生到 `.claude/skills/release-checklist/`（Claude Code 技能）与 `.iflow/workflows/release-checklist/`（iFlow workflow）。

## 案例 5：新增斜杠命令

**需求**：`/deploy` 一键构建部署。

新建 `.ai/commands/deploy.md`：

```markdown
---
description: 构建并部署到测试环境
allowed-tools: [Bash(npm:*), Bash(scp:*)]
---

按顺序执行：1) 运行全量测试；2) 构建 $ARGUMENTS 指定的目标；3) 部署到测试环境并汇报结果。
```

跑通用流程后派生到 `.claude/commands/`、`.iflow/commands/`。

**Gemini 补充**（命令是 TOML 格式，需在工具专属源手工建一份）`.ai/tools/gemini/commands/deploy.toml`：

```toml
description = "构建并部署到测试环境"
prompt = "按顺序执行：1) 运行全量测试；2) 构建 $ARGUMENTS 指定的目标；3) 部署到测试环境并汇报结果。"
```

## 案例 6：给单个工具加私有规则

**需求**：Cursor 中凡触及 `*.svg` 的改动需检查 viewBox。

新建 `.ai/tools/cursor/rules/svg.mdc`：

```markdown
---
description: SVG 文件修改规则
globs: "**/*.svg"
alwaysApply: false
---

修改 SVG 时必须检查 viewBox 设置与 fill 继承，避免破坏主题色变量。
```

跑通用流程后成为 `.cursor/rules/svg.mdc`——Cursor 只在触及匹配文件时自动激活（`globs` 模式），不占常驻上下文。

## 案例 7：给 Claude Code 配置项目级设置

**需求**：允许 `npm test` 免确认执行。

新建 `.ai/tools/claude/settings.json`：

```json
{
  "permissions": {
    "allow": ["Bash(npm test:*)", "Bash(node --test:*)"]
  }
}
```

跑通用流程后即 `.claude/settings.json`。

**注意区分**：团队共享配置走上面流程；**个人私有**设置（如自己的 API key 环境）直接放 `.claude/settings.local.json`——不入 `.ai/`，且已被 `.gitignore` 忽略。另有跨工具的本地覆盖约定：根目录 `AGENTS.local.md` / `CLAUDE.local.md`（同样已被忽略），支持该约定的工具会读取，适合放个人临时指令。

## 案例 8：新增兼容一个 AI 工具（以 Windsurf 为例）

① 在 `.ai/sync.mjs` 的 `STUB_TARGETS` 数组登记：

```js
{ tool: 'Windsurf', file: '.windsurf/rules/project_rules.md' },
```

② 新建 `.ai/tools/windsurf/README.md`（写该目录用途即可，「生成产物」标头会自动前置）。

③ 跑通用流程 → 生成 `.windsurf/rules/project_rules.md`（引用存根）与 `.windsurf/README.md`。

> 若新工具直接读 `AGENTS.md`（如 Amp、Goose、Kimi Code），则**任何登记都不需要**，天然已覆盖。

## 案例 9：让 AI 工具代劳（推荐）

对所有工具直接下自然语言指令即可，**说意图、不说文件**：

- **Claude Code / iFlow / Gemini**：「把分支模型改成 trunk-based」——自动触发 `ai-config` 技能 / `ai-config-editor` 子代理；
- **Cursor / Trae / Qoder / ZCode / Codex 等**：「按 `.ai-memory/playbooks/ai-config.md` 的流程，给编码规范的命名约定表加 Go 后端例外」。

AI 工具会自动执行五步：读手册 → 只改 `.ai/` 源 → `node .ai/sync.mjs` → `--check` 校验 → 汇报「源改动 + 派生变化 + 校验输出」。

> 📖 **完整的 21 条提示词案例库（含预期行为、防偷懒核对单）见 [PROMPTS.md](PROMPTS.md)**；**分工具教程见 [TUTORIAL.md](TUTORIAL.md)**——这是本体系的核心用法：AI 只需接收意图，规矩与流程已内置在仓库里。

---

## 入口的两种模式

| 模式 | 命令 | 形态 | 何时用 |
|---|---|---|---|
| **引用存根（默认）** | `node .ai/sync.mjs` | ~1KB 小文件，打开即见引用，跨平台无坑 | 默认 |
| 符号链接 | `node .ai/sync.mjs --links` | 12 字节链接，磁盘零拷贝，但编辑器中显示为全文 | Unix/macOS 追求极致单拷贝时 |

两种模式随时可互相切换（重跑对应命令），`--check` 对两种形态都通过。

## 故障排查

| 症状 | 原因 | 处理 |
|---|---|---|
| `git commit` 被拒绝 | `.ai/` 改动未派生 | `node .ai/sync.mjs` 后重新 `git add -A` 提交 |
| CI（ai-rules-sync）红灯 | 同上（远端防线） | 本地跑同步后重新推送 |
| 提示「❌ xxx 与 .ai/ 不同步」 | 有人手改了派生文件 | `node .ai/sync.mjs` 覆盖修复（手改内容请移植回 `.ai/` 源） |
| 提示「孤儿派生文件」 | `.ai/` 里的源被删了，派生物残留 | 按提示路径手动删除残留文件 |
| 入口文件变成一行路径文本 | 无 symlink 支持的机器检出链接损坏（`--links` 模式） | `node .ai/sync.mjs` 自动修复为存根 |
| `--check` 在不同机器结果不同 | 行尾符差异 | 确认 `.gitattributes` 未被删除 |

## 相关文档

| 文档 | 内容 |
|---|---|
| [.ai/TUTORIAL.md](TUTORIAL.md) | **分工具教程**：11 类工具的读取入口、冒烟测试、维护方式 |
| [.ai/PROMPTS.md](PROMPTS.md) | **提示词案例库**：21 条让 AI 自维护的指令 + 验收核对单 |
| [.ai/README.md](README.md) | 体系结构 / 派生一览 / 联动防线 / 新增工具 |
| [.ai-memory/playbooks/ai-config.md](../.ai-memory/playbooks/ai-config.md) | AI 工具执行配置修改的五步流程 |
| [根 README.md](../README.md) | 项目介绍与厂商声明 |
