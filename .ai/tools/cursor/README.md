# .cursor/ — Cursor

- 全局规范：`rules/project.mdc` 为薄存根（引用 `.ai/RULES.md`；`alwaysApply: true`，frontmatter 位于文件首行）。
- Cursor 私有规则（如按 glob 生效的 `.mdc`）：源在 `.ai/tools/cursor/rules/`（用法见 `.ai/tools/README.md`）。
- 需要 `.cursorignore` 时：放 `.ai/tools/cursor/`（同步到本目录根）。
- Cursor 无项目级 agents/skills/commands 目录，跨工具能力经 `AGENTS.md` / 规则间接约束。
- 本说明位于 `.cursor/` 根下而非 `rules/` 内，不会被当作规则加载。
