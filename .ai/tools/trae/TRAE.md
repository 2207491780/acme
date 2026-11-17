# .trae/ — 字节 Trae

- **规则入口**：`rules/project_rules.md` 为引用存根（官方 frontmatter `alwaysApply: true` + `description`，正文纯指针指向 `.ai/RULES.md`，不重复铁律节选）。Trae 自动加载 `rules/` 下全部 md（递归 ≤3 层）；规则仅自然语言、不解析链接/导入，指针靠 Agent 任务内主动读源。
- **根 AGENTS.md / CLAUDE.md**：Trae 兼容但默认不加载，需在「设置 > 规则 > 导入设置」手动开启「将 AGENTS.md 包含在上下文中」开关；开启后与 rules 同时生效（本仓库 rules 已去重铁律，避免双份加载）。
- **公共池引用存根**（官方项目级目录，磁盘零重复）：
  - `agents/<name>.md`：Trae Subagent，frontmatter 已适配（`tools` 数组→逗号分隔字符串、移除 `model: inherit`，Trae 缺省即继承主 Agent 模型）；需在「设置 > Beta > Subagents」开启「启用 Subagents 目录」。
  - `skills/<name>/SKILL.md`：项目技能，frontmatter 原样保留供注册；辅助文件（scripts/templates）全量拷贝。
  - `commands/<name>.md`：项目斜杠命令，文件名即命令名，支持子目录嵌套（≤3 层）。
- **Trae 私有规则**（如 SOLO 模式构建流程）：源在 `.ai/tools/trae/rules/`（用法见 `.ai/tools/TOOL-SOURCES.md`）。
- **MCP / 设置**：官方支持项目级 MCP 与 `.trae/` 配置；当前未配置工具级源，需要时在 `.ai/tools/trae/` 建源派生。
- 本说明位于 `.trae/` 根下而非 `rules/` 内，不会被当作规则加载。

> 官方释义 vs 本仓库适配的完整差异矩阵见 ADR `2026-11-17-04-trae-official-alignment.md`。
