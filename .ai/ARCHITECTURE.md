# .ai/ — AI Coding 配置中心（唯一事实源）

所有 AI-Coding 相关内容**只允许在本目录修改**。仓库其他位置的 AI 相关文件一律是派生产物（链接/拷贝）或对本目录的直接消费，请勿手改。

> 本文件是这套体系的**唯一权威说明**：目录结构、使用流程、派生一览、联动防线、扩展方法都在这里维护；仓库其他文档只做引用。
> 📖 **使用指南（含 9 个实操案例与故障排查）见 [GUIDE.md](GUIDE.md)**；项目介绍见[根 README.md](../README.md)。

## 目录结构

```text
.ai/
├── ARCHITECTURE.md # 本文件（体系唯一权威说明）
├── GUIDE.md       # 使用指南（9 个实操案例 + 故障排查）
├── TUTORIAL.md    # 分工具教程（11 类工具的入口、冒烟测试、维护方式）
├── PROMPTS.md     # 提示词案例库（21 条让 AI 自维护的指令 + 验收核对单）
├── sync.mjs       # 派生引擎：node .ai/sync.mjs（--check 为校验模式）
├── worktree.mjs   # 并行开发工作树管理：node .ai/worktree.mjs add|remove|list（口径见 playbooks/parallel-dev.md）
├── ai-coding-config.yml # 行为/引擎开关 + 环境上下文（源，sync 读取，不派生；见下「行为/引擎开关」）
├── RULES.md       # ⚙️ 生成：公共规范唯一完整副本（由 rules/ 按文件名排序拼接）
├── rules/         # 公共规范源（AI 协作准则 / 编码规范 / Git 工作流 / 项目上下文）
├── scopes/        # 分层 AGENTS.md 源 → 派生到仓库同路径（就近生效，见 SCOPED-RULES.md）
├── agents/        # 跨工具子代理公共池（格式见 AGENT-POOL.md）
├── skills/        # 跨工具技能公共池（格式见 SKILL-POOL.md）
├── commands/      # 跨工具命令公共池（格式见 COMMAND-POOL.md）
├── tools/         # 各工具专属配置源（用法见 TOOL-SOURCES.md）
├── env/           # 环境与 MCP 连接定义 dev/test/prod（AI 按需读取，见 ENV-GUIDE.md）
├── ci/            # CI/CD 工作流源 → .github/workflows/（ci / cd / ai-rules-sync，见 CI-GUIDE.md）
├── tests/         # 回归测试（引擎 / 钩子 / 工作树）：node --test ".ai/tests/*.test.mjs"（CI ai-config 作业运行）
└── hooks/         # git 钩子：pre-commit（AI 配置完整性）/ commit-msg（提交规范）/ pre-push（main 保护+强推拦截）；node .ai/hooks/enable.mjs 启用
```

## 使用流程（强制联动）

> ⚠️ 铁律的规范表述见 `.ai/rules/ai-guidelines.md`「AI 配置联动更新」：AI-Coding 内容只在本目录改；改完必派生；提交前必过校验。

```bash
# 一次性初始化
git init && node .ai/hooks/enable.mjs
# Linux/macOS 协作者首次提交前确保钩子可执行位：
# git update-index --chmod=+x .ai/hooks/pre-commit .ai/hooks/commit-msg .ai/hooks/pre-push

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
| `.ai/tools/<工具>/**` | 对应工具目录 | 拷贝派生（目录说明文件如 `CLAUDE-CODE.md` 自动前置「生成产物」标头） |
| `.ai/ci/**` | `.github/workflows/` | 拷贝派生 |

## 入口分发：默认引用存根，可选符号链接

| 模式 | 触发方式 | 形态 | 说明 |
|---|---|---|---|
| **引用存根（默认）** | `node .ai/sync.mjs` | ~1KB 存根：必读指引 + 铁律节选，指向 `.ai/RULES.md` | 文件本身就是**肉眼可见的引用**，任何编辑器打开都不显示重复内容；Claude Code 经 `@.ai/RULES.md` 导入自动内联全文 |
| 符号链接（可选） | `--links` / `AI_SYNC_LINK=1`，或配置 `entryMode: symlink` | 🔗 链接 → `.ai/RULES.md`（12 字节） | 磁盘零拷贝；但编辑器中会**显示为全文**（看似重复），且无 symlink 支持的机器上 git 会检出成损坏文本 |

两种模式下 `--check` 均能通过（链接校验目标、存根校验内容）；从任一模式切换到另一模式，运行对应命令即可自动转换。

> 取舍理由（为何默认存根、为何公共池拷贝派生等）见 [../.ai-memory/decisions/](../.ai-memory/decisions/ADR-GUIDE.md)——决策只增不改，新决策取代旧决策时在记录开头标注。

## 行为/引擎开关（ai-coding-config.yml）

[ai-coding-config.yml](ai-coding-config.yml) 是**可选派生引擎行为**的开关源（仿 [env/](env/ENV-GUIDE.md)：源、被 `sync.mjs` 读取、**不派生**），另含 `environment` 环境上下文段（当前运行环境定义文件的指针，AI 调试前读取所指 `.ai/env/` 定义；引擎只校验不消费）。改后必须重跑 `node .ai/sync.mjs`；钩子与 CI 调用同一脚本，自动生效（无需改工作流文件）。

| 开关 | 取值 | 默认 | 作用 |
|---|---|---|---|
| `engine.entryMode` | `stub` \| `symlink` | `stub` | 根部/分层入口默认形态；CLI `--links` / `AI_SYNC_LINK=1` 优先（强制 symlink），无符号链接权限时自动降级 stub |
| `engine.orphanDetection` | 布尔 | `true` | 是否在 sync/--check 扫描工具目录孤儿派生文件；关闭会削弱联动防线 |
| `engine.rulesBudgetBytes` | 正整数 | `8192` | `RULES.md` 拼接后的字节预算上限 |

**环境上下文（`environment.*`）**——不是引擎开关，是当前运行环境定义文件的指针；AI 在调试任务前读取所指 `.ai/env/` 定义，切换环境时改本文件并重跑 sync：

| 键 | 取值 | 默认 | 作用 |
|---|---|---|---|
| `environment.file` | `.ai/env/` 下的 `.yaml` 文件名 | `local.yaml` | 当前运行环境（本地调试时后端连接的环境）：`local.yaml`=本地中间件（PG/Redis/Nacos）；`cloud-*.yaml`=云端 k3s 环境（命名与权限见 [ENV-GUIDE](env/ENV-GUIDE.md)） |

- **缺省即现状**：文件缺失或键缺省时完全向后兼容（`.ai/tests/` 回归锁定）。
- **严格校验**：未知键 / 错误类型 / 非法枚举 / 数组 / Tab 等在**写入前**报错；无外部依赖（内置极简 YAML 子集解析，仅两层标量映射 + 注释）。
- **不设开关的部分**：核心派生（rules / 入口 / scopes / pools / tools / ci）是「全工具联动」铁律的保证，始终开启；AI 协作红线与强制规范在 `.ai/rules/`，**不可**在此关闭。取舍见 [../.ai-memory/decisions/2026-11-12-06-ai-coding-config.md](../.ai-memory/decisions/2026-11-12-06-ai-coding-config.md)；environment 段扩展见 [../.ai-memory/decisions/2026-11-13-09-env-debug-target-config.md](../.ai-memory/decisions/2026-11-13-09-env-debug-target-config.md)，语义修正见 [../.ai-memory/decisions/2026-11-13-10-env-debug-backend-semantics.md](../.ai-memory/decisions/2026-11-13-10-env-debug-backend-semantics.md)，简化为 file 指针见 [../.ai-memory/decisions/2026-11-13-11-env-file-pointer.md](../.ai-memory/decisions/2026-11-13-11-env-file-pointer.md)。

## 过程性内容落盘（在 `.ai-memory/`，独立于本配置工程）

知识与记忆的落盘统一在根目录 [.ai-memory/](../.ai-memory/KNOWLEDGE.md)（decisions 定稿 / playbooks 手册 / drafts 草稿），与本目录（配置工程）分离；只有**约束**留在本目录 `.ai/rules/`（需派生进工具入口）。

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
| 本地层 | `.ai/hooks/`：pre-commit 校验 AI 配置完整性；commit-msg 校验提交规范；pre-push 拦截直推 `main` 与强推 | 违规提交/推送被拒绝 |
| 远端层 | `.ai/ci/` 派生出的 `.github/workflows/ai-rules-sync.yml` 在 push / PR 时校验 | 不同步的推送/合并被阻断 |

## CI/CD（工作流源在 [ci/CI-GUIDE.md](ci/CI-GUIDE.md)）

| 工作流 | 触发 | 职责 |
|---|---|---|
| `ci.yml` | push(main) / PR | 门禁：AI 配置同步校验 + 派生引擎回归测试（`.ai/tests/`）+ 应用类型检查/测试/构建 |
| `cd.yml` | dispatch 选环境 / `v*` 标签 → prod | 构建 → `env-stack/scripts/deploy.sh` 部署 → 健康检查；prod 走 GitHub Environment 审批 |
| `ai-rules-sync.yml` | 触及 AI 配置相关文件 | 联动防线·远端层 |

## 新增一个 AI 工具的兼容

1. 若该工具读 `AGENTS.md` —— 无需任何改动，已覆盖。
2. 否则：
   - 在 `.ai/sync.mjs` 的 `LINK_TARGETS`（根部入口：存根/链接）/ `STUB_TARGETS`（需专属格式的入口）/ `TOOLS`（专属目录）登记；
   - 建 `.ai/tools/<工具>/<工具名>.md`（如 `CURSOR.md`；标头自动生成，只写工具特有要点）；
   - 运行 `node .ai/sync.mjs`。

## MCP 服务器配置

| 层级 | 源位置 | 说明 |
|---|---|---|
| 环境级端点（跨工具） | `.ai/env/{local,cloud-dev,cloud-test,cloud-prod}.yaml`（见 [ENV-GUIDE.md](env/ENV-GUIDE.md)） | 当前为空模板；AI 按需读取，各工具原生 MCP 配置由此适配 |
| 工具级配置 | `.ai/tools/<工具>/`（按需新增，如 `cursor/mcp.json`、`gemini/settings.json`，镜像目标路径） | **当前未配置**任何工具级 MCP；需要时在此建源，经 `node .ai/sync.mjs` 派生到对应工具目录 |
| Claude Code（如需） | 根目录 `.mcp.json` | 例外：根级文件，手工维护 |
| ZCode | `.zcode/` 目录（工作区作用域） | ZCode 自管理 |

## 支持的厂商

见[根 README.md](../README.md) 的「支持的厂商」声明（仓库门面信息，随根 README 维护）。
