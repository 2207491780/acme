# .gemini/ — Gemini CLI / Antigravity

- 项目规范入口：根目录 `GEMINI.md` 为引用存根（指向 `.ai/RULES.md`；`--links` 模式可切换为符号链接）。
- 工具专属配置（`settings.json` 含权限/MCP、私有 agents）：源在 `.ai/tools/gemini/`（用法见 `.ai/tools/README.md`）。
- 命令：Gemini 使用 **TOML** 格式——公共命令池的 md 不直接派生到这里，需手工转换为 `.ai/tools/gemini/commands/*.toml`（映射说明见 `.ai/commands/README.md`）。
- 跨工具共享的 agents 自动派生到本目录 `agents/`。
