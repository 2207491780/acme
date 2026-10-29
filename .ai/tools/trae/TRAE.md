# .trae/ — 字节 Trae

- 全局规范：`rules/project_rules.md` 为薄存根（引用 `.ai/RULES.md`）。Trae 自动加载 `rules/` 目录下全部 md 文件。
- Trae 私有规则（如 SOLO 模式构建流程）：源在 `.ai/tools/trae/rules/`（用法见 `.ai/tools/TOOL-SOURCES.md`）。
- Trae 无项目级 agents/skills/commands 目录，跨工具能力经规则间接约束。
- 本说明位于 `.trae/` 根下而非 `rules/` 内，不会被当作规则加载。
