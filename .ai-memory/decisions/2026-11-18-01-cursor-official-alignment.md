# Cursor 派生机制对齐官方配置方案（纯指针规则 + 三池引用存根）

日期：2026-11-18

## 背景

此前 `.ai/tools/cursor/CURSOR.md` 断言「Cursor 无项目级 agents/skills/commands 目录」，派生仅覆盖 `.cursor/rules/project.mdc`（内联铁律节选的 `alwaysApply` 存根）。按 [cursor.com/docs/rules](https://cursor.com/docs/rules)、[skills](https://cursor.com/docs/skills)、[subagents](https://cursor.com/docs/subagents)、[hooks](https://cursor.com/docs/hooks) 与 Plugins reference（2026-11-18 核实），该断言已过时：

1. Cursor **官方同时**读取根/嵌套 `AGENTS.md` 与 `.cursor/rules/*.mdc`（四类生效：Always / Intelligently / globs / Manual）。旧 `project.mdc` 内联铁律节选与根 `AGENTS.md` 入口存根**重复声明**。
2. 规则内 `@path` 提及**不会内联**文件内容（Agent 用工具按需读取）——与 Claude Code / CodeBuddy 的 `@` 导入记忆机制不同；不能指望 `@.ai/RULES.md` 自动展开全文。
3. 官方项目级目录：`.cursor/agents/`、`.cursor/skills/`、`.cursor/commands/`；另兼容发现 `.claude/skills|agents`（同名时 `.cursor/` 优先）。**Commands 无 Claude 兼容路径**，必须落在 `.cursor/commands/` 才有 `/` 命令。
4. 另有 `.cursor/hooks.json`（Agent 生命周期钩子）、`.cursor/mcp.json`、Team Rules（仪表盘）、User Rules（账户级）等，属工具/团队自管理面。

## 决定

1. **rules 存根改纯指针**：`.cursor/rules/project.mdc` 保留 `alwaysApply: true` frontmatter，正文只指向 `.ai/RULES.md` 与根/嵌套 `AGENTS.md`，**不重复**铁律节选与多代理流程开关；注明 `@` 提及不内联、靠 Agent 主动读源。
2. **三池引用存根**：引擎 POOLS 新增 `.cursor/agents|skills|commands` 目标，`makePoolRefStub(..., 'Cursor')`——frontmatter 原样保留（无 frontmatter 时合成最小 `name`/`description`），正文仅指针指向 `.ai/<pool>/<rel>`；技能辅助文件（非 .md）照旧全量拷贝。磁盘零重复正文。
3. **agents frontmatter 不改编**：Cursor 官方字段为 `name`/`description`/`model`/`readonly`/`is_background`；公共池的 `tools: [...]` 为 Claude 约定，Cursor 未声明——原样保留、运行时忽略；`model: inherit` 为 Cursor 官方缺省，无需像 Trae 那样剥离。
4. 文档同步：`CURSOR.md` / `ARCHITECTURE.md` 派生一览 / `TUTORIAL.md` §3 与对照表 / `TOOL-SOURCES.md` / 根 README 厂商表。
5. 回归锁定：`sync.test.mjs` 新增 `should_derive_cursor_entries_as_official_aligned_references`；`should_inject_team_leader_trigger_per_mode_into_entry_stubs` 不再要求 `project.mdc` 含多代理开关（改由 `AGENTS.md` 承载）。

## 兼容矩阵（官方释义 vs 本仓库适配，2026-11-18 核实）

| 能力 | 官方支持情况 | 本仓库适配 |
|---|---|---|
| 根 / 嵌套 `AGENTS.md` | ✅ 自动识别；nested 就近生效、更具体者优先 | 根入口存根承载铁律节选 + 多代理开关；`.ai/scopes/**` 派生分层 `AGENTS.md` |
| Rules `.cursor/rules/*.mdc` | ✅ 必须 `.mdc`；frontmatter 四类生效；规则内 `@` **不内联**；建议 <500 行、引用文件而非复制 | `project.mdc` 纯指针 + `alwaysApply: true`；私有 glob 规则源在 `.ai/tools/cursor/rules/` |
| Skills `.cursor/skills/<name>/SKILL.md` | ✅ 官方项目级；亦兼容 `.claude/skills/`、`.agents/skills/`；frontmatter `name`+`description` 必填，可选 `paths` / `disable-model-invocation` | 引用存根；辅助文件全量拷贝。同名时与 `.claude/skills/` 双发现——以 `.cursor/` 优先口径依赖官方/兼容层 |
| Commands `.cursor/commands/<name>.md` | ✅ 文件名即 `/` 命令；**无** `.claude/commands` 兼容发现 | **必须**派生引用存根（否则 `/req` 不可用）。源内 `$ARGUMENTS` 为 Claude 族替换约定；Cursor 侧由 Agent 将用户附带参数映射，非原生宏替换 |
| Agents `.cursor/agents/<name>.md` | ✅ frontmatter `name`/`description`/`model`/`readonly`/`is_background`；兼容 `.claude/agents/`，同名 `.cursor/` 优先 | 引用存根；`tools` 字段保留但不映射（Cursor 未声明该键） |
| Hooks `.cursor/hooks.json` | ✅ 项目级 Agent/Tab/workspace 钩子；Cloud Agents 会加载 | **当前未配置**；需要时在 `.ai/tools/cursor/` 建源派生。仓库 git 钩子（`.ai/hooks/`）是另一机制 |
| MCP `.cursor/mcp.json` | ✅ 项目级 | **当前未配置**（与 ARCHITECTURE「MCP 服务器配置」一致） |
| Team / User Rules | ✅ 仪表盘 / 账户级，不入仓库 | 不参与派生 |
| 遗留 `.cursorrules` | ⚠️ 官方标明将弃用 | **不使用**；一律走 `.cursor/rules/*.mdc` |

## 被否的选项

- **rules 存根继续内联铁律节选**：否决——Cursor 双通道加载 AGENTS.md 与 alwaysApply rules，内联即重复声明；官方 best practice 亦要求引用文件而非复制。
- **三池全量镜像进 `.cursor/`**：否决——用户要求「能用引用则用引用」；Agent/Skill/Command 正文由带工具权限的 Agent 回读源即可生效（Qoder/CodeBuddy/Trae 同取舍）。
- **仅依赖 `.claude/skills|agents` 兼容发现、不派生 `.cursor/` 池**：否决——偏离官方项目路径；且 **commands 无 Claude 兼容路径**，不派生则 `/req` 缺失。
- **rules 存根用 `@.ai/RULES.md` 指望自动内联**：否决——官方明示规则内 `@` 提及不内联内容；只能自然语言指针 + Agent 主动读（与 Trae/Qoder 同约束）。
- **为 Cursor 剥离 agents 的 `tools` 字段**：否决——未知键通常被忽略；剥离会让引用存根 frontmatter 与池源不一致，增加 transform 面；差异在本矩阵如实标注即可。
