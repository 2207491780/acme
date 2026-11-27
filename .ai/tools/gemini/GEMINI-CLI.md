# .gemini/ — Gemini CLI（Google）

- 项目规范入口：根目录 `GEMINI.md` 为引用存根（指向 `.ai/RULES.md`；`--links` 模式可切换为符号链接）。
- 工具专属配置（`settings.json` 含权限/MCP、私有 agents）：源在 `.ai/tools/gemini/`（用法见 `.ai/tools/TOOL-SOURCES.md`）。
- 命令：Gemini CLI 使用 **TOML** 格式——公共命令池的 md 不直接派生到这里，需手工转换为 `.ai/tools/gemini/commands/*.toml`（映射说明见 `.ai/commands/COMMAND-POOL.md`）。
- 跨工具共享的 agents 自动派生到本目录 `agents/`（派生时自动映射 frontmatter 内置工具名并移除 `model: inherit`）。
- **Antigravity 区分**：Google Antigravity 的工作区定制目录为 `.agents/`（通过 `.agents/skills.json` 直引 `.ai/skills`，规则走根/分层 `AGENTS.md` + `GEMINI.md`），详见 `.agents/ANTIGRAVITY.md` 与 ADR `2026-11-27-05-antigravity-official-alignment.md`。
