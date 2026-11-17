# Qoder 派生机制对齐官方配置方案（引用存根、不重复声明）

日期：2026-11-17

## 背景

此前 `.ai/tools/qoder/QODER.md` 断言「Qoder 无项目级 agents/skills/commands 目录」，派生仅覆盖 `.qoder/rules/project_rules.md`（内联铁律节选的 frontmatter 存根）。2026-11-17 按 docs.qoder.com 官方文档逐项核实，该断言已过时：

1. Qoder Desktop 官方支持项目级 `.qoder/skills/<name>/SKILL.md` 与 `.qoder/commands/<name>.md`（文件名即命令名，子目录映射冒号名）；`.qoder/agents/<name>.md` 是 **CLI 官方口径**，Desktop 自定义 agent 走设置界面管理，项目文件加载官方未声明。
2. Qoder 同时自动读取根 `AGENTS.md` 与 `.qoder/rules/`（本会话 loaded_context 实证双份加载）；官方口径**冲突时 rules 优先**。旧规则存根内联铁律节选，与入口存根重复声明。
3. Qoder 规则**仅支持自然语言，不解析链接/导入**（与 Claude 的 `@` 导入不同）；全部激活规则合计 ≤ 10 万字符。
4. 本仓库已有 `.zcode/` 引用存根先例（frontmatter 原样 + 正文指针，ADR 2026-11-14-20），可同口径复用。

## 决定

1. **rules 存根改纯指针**：`.qoder/rules/project_rules.md` 保留 `alwaysApply: true` frontmatter，正文只指向 `.ai/RULES.md` 与根 `AGENTS.md`，不重复铁律节选（双份加载去重）；注明 rules 不解析链接、指针靠 Agent 任务内主动读源。
2. **三池引用存根**：引擎 POOLS 新增 `.qoder/agents|skills|commands` 目标，`makeQoderStub` transform——frontmatter 原样保留供注册（无 frontmatter 时按路径合成最小 `name`/`description` 头），正文仅指针指向 `.ai/<pool>/<rel>`，并说明源内相对路径（scripts/、templates/）的解析基准；非 .md 辅助文件照旧全量拷贝（渐进披露本地可用）。与 `.zcode/` 同口径，磁盘零重复。
3. **agents 工具名不映射**：Qoder CLI 工具名与 Claude 兼容（Read/Grep/Glob/Bash/Write/Edit/WebFetch/WebSearch/Agent），池源 frontmatter 直接可用（区别于 Gemini 的 GEMINI_TOOL_MAP，ADR 2026-11-14-24）。
4. 文档同步：QODER.md 重写（含 Hooks/MCP/settings 官方位置与「当前未配置」声明）、ARCHITECTURE.md 派生一览、TUTORIAL.md §8 与快速对照表。
5. 回归锁定：`sync.test.mjs` 新增 `should_derive_qoder_entries_as_non_duplicating_references`（rules 纯指针不含铁律节选 + 三池 frontmatter 保留/正文零重复 + 无 frontmatter 合成头 + 派生标记在位 + --check 幂等）。

## 兼容矩阵（官方释义 vs 本仓库适配，2026-11-17 核实）

| 能力 | 官方支持情况 | 本仓库适配 |
|---|---|---|
| Rules `.qoder/rules/*.md` | ✅ Desktop/CLI；frontmatter 决定激活方式（alwaysApply 等四类）；仅自然语言、**不解析链接/导入**；激活规则合计 ≤ 10 万字符 | 纯指针存根 + `alwaysApply: true`；靠 Agent 主动读 `.ai/RULES.md` 生效 |
| 根 `AGENTS.md` | ✅ 自动识别；与 rules 冲突时 **rules 优先** | 入口存根承载铁律节选；rules 不重复（去重） |
| Skills `.qoder/skills/<name>/SKILL.md` | ✅ Desktop 官方项目级；frontmatter name（小写字母数字连字符 ≤64）+ description（≤1024）必填；同名**用户级覆盖项目级** | 引用存根（frontmatter 原样/合成），辅助文件全量拷贝 |
| Commands `.qoder/commands/<name>.md` | ✅ Desktop 官方项目级；文件名即命令名，子目录→冒号名；frontmatter description | 引用存根（`/req` 可用） |
| Agents `.qoder/agents/<name>.md` | ⚠️ **CLI 官方口径**；Desktop 自定义 agent 在设置 UI 管理，项目文件加载官方未声明、**待新会话验证**；优先级 Built-in < User < Project < Plugin < Flag；工具名与 Claude 兼容 | 照常派生引用存根（CLI 可用；Desktop 验证缺口如实标注，不冒称已生效） |
| Hooks / MCP / settings | ✅ `.qoder/settings.json`（项目共享）/ `settings.local.json`（本机），与用户级 `~/.qoder/settings.json` 合并；`hooks`、`mcpServers` 顶层键 | **当前未配置**，无 settings 源；需要时在 `.ai/tools/qoder/` 建源派生。仓库 git 钩子（`.ai/hooks/`）是另一机制 |
| 记忆 | Desktop auto-memory 由应用管理（`~/.qoder/memory` + 项目级目录） | 不参与仓库派生；仓库知识落盘 `.ai-memory/`，跨会话差异另记 auto-memory |

## 被否的选项

- **rules 存根继续内联铁律节选**：否决——Qoder 双份加载 AGENTS.md 与 rules，内联即重复声明，白耗 10 万字符预算；官方冲突序 rules 优先，纯指针不损失约束力。
- **三池全量镜像（同 .claude/.codebuddy）**：否决——用户要求「能用引用则用引用」；Qoder 规则/技能正文由 Agent 读取，指针存根即可生效，全量拷贝徒增磁盘重复与漂移面（.zcode 先例同取舍）。
- **rules 存根用 `@.ai/RULES.md` 导入语法**：否决——Qoder 不解析链接/导入（官方明示），Claude 式导入在此静默失效；只能自然语言指针 + Agent 主动读。
- **为 Desktop 未验证的 agents 目录跳过派生**：否决——CLI 官方支持且 frontmatter 兼容，派生无害；验证缺口在 QODER.md/TUTORIAL/本 ADR 如实标注，待新会话冒烟后补记。