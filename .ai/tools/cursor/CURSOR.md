# .cursor/ — Cursor

- **规范入口**：根/嵌套 `AGENTS.md`（Cursor 官方自动读取）+ `rules/project.mdc`（`alwaysApply: true` **纯指针**，不重复铁律节选；规则内 `@` 提及不内联，靠 Agent 主动读 `.ai/RULES.md`）。
- **公共池引用存根**：`agents/`、`skills/`、`commands/` 由引擎从 `.ai/` 公共池派生（frontmatter 保留供注册，正文指针指向源；能用引用则用引用）。同名时 Cursor 亦兼容发现 `.claude/skills|agents`，以 `.cursor/` 优先。
- **私有规则**：按 glob / 智能 / 手动生效的 `.mdc` 源放 `.ai/tools/cursor/rules/`（用法见 `.ai/tools/TOOL-SOURCES.md`）。
- **`.cursorignore` / `mcp.json` / `hooks.json`**：需要时放 `.ai/tools/cursor/` 对应镜像路径后派生；**当前未配置** MCP 与 Agent hooks。仓库 git 钩子（`.ai/hooks/`）是另一机制。
- 本说明位于 `.cursor/` 根下而非 `rules/` 内，不会被当作规则加载。

> 官方释义 vs 本仓库适配的完整差异矩阵见 ADR `2026-11-18-01-cursor-official-alignment.md`。

