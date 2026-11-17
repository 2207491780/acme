# .codebuddy/ — 腾讯 CodeBuddy Code（CLI / IDE）

官方口径（docs.codebuddy.ai「.codebuddy 目录结构说明」「管理 CodeBuddy 的记忆」，核实于 2026-11-17；差异与取舍见 ADR 2026-11-17-03）：

- **规范入口（官方 `@` 导入引用）**：根 `CODEBUDDY.md` 用官方 import 语法 `@.ai/RULES.md` 自动内联完整规范（与 Claude Code 存根同机制），正文不重复铁律节选，仅保留一句兜底指针与引擎注入的多代理流程开关。官方加载顺序：项目 `CODEBUDDY.md` 存在时优先于 `AGENTS.md`。
- **公共池引用存根**：`agents/`、`skills/`、`commands/` 由 `.ai/agents|skills|commands/` 公共池自动派生——frontmatter 原样保留供官方注册（项目级 > 用户级，同名项目级覆盖），正文仅指针指向池源（与 `.qoder/`、`.zcode/` 存根同口径，磁盘零重复）。官方目录格式：`.codebuddy/agents/<name>.md`、`.codebuddy/skills/<name>/SKILL.md`（辅助文件全量拷贝）、`.codebuddy/commands/<name>.md`（子目录映射冒号命令名）。
- **localOnly（与官方差异）**：官方建议项目 `.codebuddy/` 提交共享；本仓库 git 忽略、本机生效——目录与工具本机运行数据（`teams/` 等）混居，且 CI 全新检出无此目录。`--check` 对缺失跳过不判错，存在则必须与 `.ai/` 一致（防漂移）。
- **官方支持但当前未配置**：`.codebuddy/rules/*.md`（frontmatter `alwaysApply`/`paths`/`enabled`）、`.codebuddy/settings.json`（项目共享）/ `settings.local.json`（本机）、根 `.mcp.json`（项目级 MCP）。需要时在 `.ai/tools/codebuddy/` 建源派生；本仓库规范正文走 `CODEBUDDY.md` `@` 导入，不另设 rules 存根（避免双份加载）。仓库 git 钩子（`.ai/hooks/`）与 CodeBuddy hooks 是两套机制。
- **记忆**：auto-memory / Typed Memory 存于 `~/.codebuddy/memories/`，由应用自管理，不参与仓库派生；仓库知识落盘 `.ai-memory/`。
- 其余 CodeBuddy 数据（如 `teams/`）工具自管理，勿放入 `.ai/`。
