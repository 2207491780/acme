# Trae 派生机制对齐官方配置方案（rules frontmatter、三池引用存根、Subagent frontmatter 适配）

日期：2026-11-17
背景关联：`2026-11-17-02-qoder-official-alignment.md`（Qoder 同类对齐先例）、`2026-11-17-03-codebuddy-official-alignment.md`（CodeBuddy 同类对齐先例，mirrorPools + 引用存根口径）。

## 背景

1. **官方配置方案已核实**（docs.trae.cn / docs.trae.ai，核实于 2026-11-17）：
   - **规则 `.trae/rules/*.md`**：靠 frontmatter 决定生效方式——`alwaysApply`（始终生效）/ `globs`（指定文件）/ `description`（智能生效）/ 手动触发；`scene: git_message` 可专用于提交信息生成。支持子目录嵌套（≤3 层）与子目录 `.trae/rules/`（就近生效）。规则仅自然语言，**不解析链接/导入**。单规则大小限制 20000 byte。优先级：用户输入 > 自定义 Agent Prompt > user_rules.md > project_rules.md。
   - **根 `AGENTS.md` / `CLAUDE.md` / `CLAUDE.local.md`**：Trae 兼容，但**默认不加载**，需在「设置 > 规则 > 导入设置」手动开启「将 AGENTS.md 包含在上下文中」「将 CLAUDE.md 包含在上下文中」开关。
   - **技能 `.trae/skills/<name>/SKILL.md`**：项目级技能，frontmatter `name` + `description`，可含辅助文件（scripts/templates）。
   - **命令 `.trae/commands/<name>.md`**：项目级斜杠命令，文件名即命令名，支持子目录嵌套（≤3 层），frontmatter 含描述。
   - **子智能体 `.trae/agents/<name>.md`**：项目级 Subagent，frontmatter 字段 `name`（必填）、`description`（必填）、`model`（可选，不支持 `inherit`，缺省即继承主 Agent 模型）、`tools`（逗号分隔字符串，非数组）、`disallowedTools`、`mcpServers`。需在「设置 > Beta > Subagents」开启「启用 Subagents 目录」。
   - **MCP / 设置**：支持项目级 MCP 与 `.trae/` 配置；hooks 另有全局/项目级配置文件机制。
2. **旧做法偏离官方且能力未利用**：
   - `.trae/rules/project_rules.md` 无 frontmatter（不符合官方四类生效方式，激活行为不确定），且内联铁律节选（与入口存根重复）。
   - 未派生公共池 agents/skills/commands 到 `.trae/`——TRAE.md 断言「Trae 无项目级 agents/skills/commands 目录」已过时。
   - 未利用官方 `scene: git_message`、子目录规则等机制。

## 决定

1. **rules 存根加官方 frontmatter + 改纯指针**：`.trae/rules/project_rules.md` 添加 `alwaysApply: true` + `description` frontmatter，正文只指向 `.ai/RULES.md` 与根 `AGENTS.md`（注明需手动开 AGENTS.md 开关），不重复铁律节选——与 Qoder 同口径，避免开启 AGENTS.md 时双份加载；未开启时靠指针引导 AI 主动读 `.ai/RULES.md`。
2. **三池引用存根**：`TOOLS.trae.mirrorPools` 新增 agents/skills/commands 三池派生：
   - skills/commands 复用 `makePoolRefStub`（frontmatter 原样保留供注册，正文指针指向 `.ai/` 池源，磁盘零重复）；
   - agents 用新增 `makeTraeAgentStub`——在引用存根基础上适配 frontmatter：`tools` 数组→逗号分隔字符串、移除 `model: inherit`（Trae 缺省即继承主 Agent 模型）；
   - 技能辅助文件（scripts/templates 等非 .md）照旧全量拷贝。
3. **暂不派生 `.trae/rules/` 额外规则 / `scene: git_message`**：规范正文走 `project_rules.md` 指针 + `.ai/RULES.md`，另设 rules 会双份加载；git commit 规范已在 `.ai/rules/git-workflow.md`，无需单独 `scene: git_message` 文件（需要时在 `.ai/tools/trae/rules/` 建源）。
4. **暂不为 scopes 派生子目录 `.trae/rules/`**：官方支持子目录就近规则，但当前 scopes 走 AGENTS.md（Trae 需开 AGENTS.md 开关），子目录 `.trae/rules/` 会增加漂移面，待 AGENTS.md 开关默认开启或有明确需求时再做。
5. 文档同步：TRAE.md 重写、ARCHITECTURE.md 派生一览、TUTORIAL.md §7 与快速对照表。
6. 回归锁定：`sync.test.mjs` 新增 `should_derive_trae_entries_as_official_aligned_references`。

## 兼容矩阵（官方释义 vs 本仓库适配，2026-11-17 核实）

| 能力 | 官方支持情况 | 本仓库适配 |
|---|---|---|
| Rules `.trae/rules/*.md` | ✅ frontmatter 四类生效方式（alwaysApply/globs/description/手动）；`scene: git_message`；递归 ≤3 层；仅自然语言、不解析链接；≤20000 byte | `project_rules.md` 纯指针存根 + `alwaysApply: true`；靠 Agent 主动读 `.ai/RULES.md`；额外规则/`scene: git_message` 暂不派生，需要时在 `.ai/tools/trae/rules/` 建源 |
| 根 `AGENTS.md` / `CLAUDE.md` | ⚠️ **兼容但默认关闭**，需在「设置 > 规则 > 导入设置」手动开启开关 | 根 `AGENTS.md` 已由 sync 派生；用户开启后与 rules 同时生效（rules 已去重铁律避免双份）；未开启时 rules 指针是唯一规范入口 |
| Skills `.trae/skills/<name>/SKILL.md` | ✅ 项目级；frontmatter name + description；可含辅助文件 | 引用存根（frontmatter 原样/合成），辅助文件全量拷贝 |
| Commands `.trae/commands/<name>.md` | ✅ 项目级；文件名即命令名；子目录嵌套 ≤3 层；frontmatter 描述 | 引用存根（`/req` 可用，执行时回读 `.ai/commands/req.md`） |
| Agents `.trae/agents/<name>.md` | ✅ 项目级 Subagent；frontmatter name/description 必填，tools 逗号分隔字符串，model 不支持 inherit；需在 Beta 开启「启用 Subagents 目录」 | 引用存根 + frontmatter 适配（tools 数组→字符串、移除 model inherit）；Subagents 开关需用户手动开启（如实标注，不冒称已生效） |
| 子目录 `.trae/rules/`（就近生效） | ✅ 官方支持 | **暂不派生**——scopes 当前走 AGENTS.md（需开开关），子目录 rules 增加漂移面；待有明确需求时再做 |
| MCP / 设置 / Hooks | ✅ 项目级 MCP、`.trae/` 配置、hooks 配置文件 | **当前未配置**工具级源；需要时在 `.ai/tools/trae/` 建源派生。仓库 git 钩子（`.ai/hooks/`）是另一机制 |
| 记忆 | Trae auto-memory 由应用管理 | 不参与仓库派生；仓库知识落盘 `.ai-memory/` |

## 被否的选项

- **rules 存根继续无 frontmatter + 内联铁律节选**：否决——无 frontmatter 激活行为不确定（官方四类生效方式依赖 frontmatter）；内联铁律与入口存根重复，开启 AGENTS.md 时双份加载。
- **三池全量镜像（同 .claude）**：否决——用户要求「能用引用则用引用」；Trae 规则/技能/subagent 正文由带工具权限的 Agent 读取，指针存根即可生效，全量拷贝徒增磁盘重复与漂移面（Qoder/CodeBuddy 先例同取舍）。
- **agents frontmatter 原样保留（tools 数组 + model: inherit）**：否决——Trae 官方要求 tools 为逗号分隔字符串、model 不支持 inherit；原样保留会导致 Subagent 解析失败或行为异常。
- **为所有 scopes 派生子目录 `.trae/rules/`**：否决——当前无明确需求，子目录 rules 增加漂移面与 sync 复杂度；待 AGENTS.md 开关默认开启或有模块级规则需求时再评估。
- **`scene: git_message` 单独派生文件**：否决——git commit 规范已在 `.ai/rules/git-workflow.md`，project_rules.md 指针已覆盖；单独文件会重复声明。需要 Trae 专属提交规范时再在 `.ai/tools/trae/rules/` 建源。
