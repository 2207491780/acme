# .qoder/ — 阿里 Qoder（Desktop / CLI）

- **规范入口（双份加载已去重）**：Qoder 自动读取根 `AGENTS.md`（入口存根：铁律节选 + 流程开关），同时加载 `rules/project_rules.md`（`alwaysApply: true` 常驻）。规则存根是**纯指针**（指向 `.ai/RULES.md` 与 `AGENTS.md`），不重复铁律——官方口径两者冲突时 rules 优先。规则仅支持自然语言、**不解析链接/导入**，指针靠 Agent 任务内主动读源生效；全部激活规则合计 ≤ 10 万字符。
- **公共池引用存根**：`skills/`、`commands/`、`agents/` 由 `.ai/skills|commands|agents/` 自动派生——frontmatter 原样保留供注册，正文仅指针指向池源（与 `.zcode/` 存根同口径，磁盘零重复）。官方项目级目录：`.qoder/skills/<name>/SKILL.md`、`.qoder/commands/<name>.md`（子目录映射冒号命令名）、`.qoder/agents/<name>.md`；同名覆盖顺序：用户级 > 项目级（skills/agents）。
- **Desktop 差异**：commands/skills 为 Desktop 官方支持；agents 目录是 CLI 官方口径，Desktop 自定义 agent 走设置界面管理，项目文件加载待新会话验证（兼容矩阵见 ADR 2026-11-17-02）。
- **Hooks / MCP / settings**：官方位置 `.qoder/settings.json`（项目共享）/ `settings.local.json`（本机），与用户级 `~/.qoder/settings.json` 合并；`hooks`、`mcpServers` 均为顶层键。**当前未配置**，故本目录无 settings 源；需要时在 `.ai/tools/qoder/` 建源派生。仓库 git 钩子（`.ai/hooks/`）是另一机制，与 Qoder hooks 无关。
- **记忆**：Desktop auto-memory 由应用管理（`~/.qoder/memory` 与项目级目录），不参与仓库派生；仓库知识落盘在 `.ai-memory/`。
- 本说明位于 `.qoder/` 根下而非 `rules/` 内，不会被当作规则加载。