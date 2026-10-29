# .claude/ — Anthropic Claude Code

- 项目规范入口：根目录 `CLAUDE.md` 为 `@.ai/RULES.md` 导入存根（Claude Code 加载时自动内联全文；`--links` 模式可切换为符号链接。规范源：`.ai/rules/`）。
- 工具专属配置（`settings.json`、私有 agents/skills/commands）：源在 `.ai/tools/claude/`（用法见 `.ai/tools/TOOL-SOURCES.md`）。
- 跨工具共享的 agents/skills/commands：源在 `.ai/agents|skills|commands/` 公共池，自动派生到本目录对应子目录。
- 例外：`settings.local.json` 为个人本地设置，**直接放本目录**（`.gitignore` 已忽略），不入 `.ai/`。
- ZCode（智谱）读取根目录 `AGENTS.md`（不读 `CLAUDE.md`）；iFlow CLI 兼容本生态——两者入口均已生成，无需单独目录。
