# .ai/ — AI Coding 配置中心（唯一事实源）

所有 AI-Coding 相关内容**只允许在本目录修改**。仓库其他位置的 AI 相关文件一律是派生产物（链接/拷贝）或对本目录的直接消费，请勿手改。

> 本文件是这套体系的**唯一权威说明**：目录结构、使用流程、派生一览、联动防线、扩展方法都在这里维护；仓库其他文档只做引用。
> 📖 **使用指南（含 9 个实操案例与故障排查）见 [GUIDE.md](GUIDE.md)**；项目介绍见[根 README.md](../README.md)。

## 目录结构

```text
.ai/
├── README.md      # 本文件（体系唯一权威说明）
├── GUIDE.md       # 使用指南（9 个实操案例 + 故障排查）
├── TUTORIAL.md    # 分工具教程（11 类工具的入口、冒烟测试、维护方式）
├── PROMPTS.md     # 提示词案例库（21 条让 AI 自维护的指令 + 验收核对单）
├── sync.mjs       # 派生引擎：node .ai/sync.mjs（--check 为校验模式）
├── RULES.md       # ⚙️ 生成：公共规范唯一完整副本（由 rules/ 按文件名排序拼接）
├── rules/         # 公共规范源（AI 协作准则 / 编码规范 / Git 工作流 / 项目上下文）
├── scopes/        # 分层 AGENTS.md 源 → 派生到仓库同路径（就近生效，见其 README.md）
├── agents/        # 跨工具子代理公共池（格式见其 README.md）
├── skills/        # 跨工具技能公共池（格式见其 README.md）
├── commands/      # 跨工具命令公共池（格式见其 README.md）
├── tools/         # 各工具专属配置源（用法见其 README.md）
├── ci/            # CI 工作流源 → .github/workflows/（GitHub 要求固定路径）
└── hooks/         # git 钩子（git config core.hooksPath .ai/hooks 直接指向，零拷贝）
```

## 使用流程（强制联动）

> ⚠️ 铁律的规范表述见 `.ai/rules/ai-guidelines.md`「AI 配置联动更新」：AI-Coding 内容只在本目录改；改完必派生；提交前必过校验。

```bash
# 一次性初始化
git init && git config core.hooksPath .ai/hooks
# Linux/macOS 协作者首次提交前确保钩子可执行位：
# git add .ai/hooks/pre-commit && git update-index --chmod=+x .ai/hooks/pre-commit

node .ai/sync.mjs          # 修改 .ai/ 后【必须】执行：一键派生到所有工具
node .ai/sync.mjs --check  # 校验一致性（提交钩子与 CI 自动执行）
```

## 派生一览（唯一权威映射；引擎定义见 sync.mjs 常量）

| 源 | 派生目标 | 形态 |
|---|---|---|
| `.ai/rules/*.md` | `.ai/RULES.md` | 拼接副本 |
| `.ai/RULES.md` | 根部 `AGENTS.md` / `CLAUDE.md` / `GEMINI.md` / `CODEBUDDY.md` | 引用存根（默认）；`--links` 可选符号链接 |
| 同上（存根形态） | `.cursor/rules/project.mdc`、`.trae/rules/project_rules.md`、`.qoder/rules/project_rules.md`、`.github/copilot-instructions.md` | frontmatter 存根（Cursor/Qoder 需 `alwaysApply`；Copilot 规避长度限制） |
| `.ai/scopes/**` | 仓库同路径 `AGENTS.md` + 同级 `CLAUDE.md`（默认 `@` 导入存根；`--links` 模式为链接） | 副本 + 引用 |
| `.ai/agents/` | `.claude/agents/`、`.iflow/agents/`、`.gemini/agents/` | 拷贝派生 |
| `.ai/skills/` | `.claude/skills/`、`.iflow/workflows/` | 拷贝派生 |
| `.ai/commands/` | `.claude/commands/`、`.iflow/commands/` | 拷贝派生 |
| `.ai/tools/<工具>/**` | 对应工具目录 | 拷贝派生（README 自动前置「生成产物」标头） |
| `.ai/ci/**` | `.github/workflows/` | 拷贝派生 |

## 入口分发：默认引用存根，可选符号链接

| 模式 | 触发方式 | 形态 | 说明 |
|---|---|---|---|
| **引用存根（默认）** | `node .ai/sync.mjs` | ~1KB 存根：必读指引 + 铁律节选，指向 `.ai/RULES.md` | 文件本身就是**肉眼可见的引用**，任何编辑器打开都不显示重复内容；Claude Code 经 `@.ai/RULES.md` 导入自动内联全文 |
| 符号链接（可选） | `node .ai/sync.mjs --links` 或 `AI_SYNC_LINK=1` | 🔗 链接 → `.ai/RULES.md`（12 字节） | 磁盘零拷贝；但编辑器中会**显示为全文**（看似重复），且无 symlink 支持的机器上 git 会检出成损坏文本 |

两种模式下 `--check` 均能通过（链接校验目标、存根校验内容）；从任一模式切换到另一模式，运行对应命令即可自动转换。

> 取舍理由（为何默认存根、为何公共池拷贝派生等）见 [../.ai-memory/decisions/](../.ai-memory/decisions/README.md)——决策只增不改，新决策取代旧决策时在记录开头标注。

## 过程性内容落盘（在 `.ai-memory/`，独立于本配置工程）

知识与记忆的落盘统一在根目录 [.ai-memory/](../.ai-memory/README.md)（decisions 定稿 / playbooks 手册 / drafts 草稿），与本目录（配置工程）分离；只有**约束**留在本目录 `.ai/rules/`（需派生进工具入口）。

| 内容 | 位置 |
|---|---|
| 必须遵守的约束（是什么） | `.ai/rules/`（拼接进所有工具入口） |
| 操作步骤（怎么做） | `.ai-memory/playbooks/` |
| 取舍与决策（为什么） | `.ai-memory/decisions/`（ADR，只增不改） |
| 工作记忆（进行中/踩坑/待沉淀） | `.ai-memory/drafts/`（草稿区，随时改写） |

强制条款见 `.ai/rules/ai-guidelines.md`「过程性内容落盘」（含 RULES.md ≤ 8KB 预算）。

## 联动保障（三道防线）

| 防线 | 机制 | 失效后果 |
|---|---|---|
| 规则层 | 强制条款位于 `.ai/rules/ai-guidelines.md`，派生注入**所有**工具入口，AI 工具自身必须遵守 | — |
| 本地层 | `.ai/hooks/pre-commit` 提交前运行 `--check`（含孤儿派生文件检测） | 不同步的提交被拒绝 |
| 远端层 | `.ai/ci/` 派生出的 `.github/workflows/ai-rules-sync.yml` 在 push / PR 时校验 | 不同步的推送/合并被阻断 |

## 新增一个 AI 工具的兼容

1. 若该工具读 `AGENTS.md` —— 无需任何改动，已覆盖。
2. 否则：
   - 在 `.ai/sync.mjs` 的 `LINK_TARGETS`（根部入口：存根/链接）/ `STUB_TARGETS`（需专属格式的入口）/ `TOOLS`（专属目录）登记；
   - 建 `.ai/tools/<工具>/README.md`（标头自动生成，只写工具特有要点）；
   - 运行 `node .ai/sync.mjs`。

## MCP 服务器配置

| 工具 | 源位置 | 派生目标 |
|---|---|---|
| Claude Code | 根目录 `.mcp.json` | 例外：根级文件，暂手工维护 |
| Cursor | `.ai/tools/cursor/mcp.json` | `.cursor/mcp.json` |
| Gemini CLI | `.ai/tools/gemini/settings.json`（`mcpServers` 字段） | `.gemini/settings.json` |
| ZCode | `.zcode/` 目录（工作区作用域） | ZCode 自管理 |
| 其他 | 以官方文档为准 | — |

## 支持的厂商

见[根 README.md](../README.md) 的「支持的厂商」声明（仓库门面信息，随根 README 维护）。
