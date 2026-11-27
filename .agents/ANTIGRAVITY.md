> ⚙️ 本目录为**生成产物**：所有 AI 配置统一维护于 [.ai/](../.ai/ARCHITECTURE.md)，由 [.ai/sync.mjs](../.ai/sync.mjs) 派生，请勿手改（本说明文件亦然）。

# .agents/ — Google Antigravity

- **规范入口**：根目录 `AGENTS.md` 与 `GEMINI.md`（均由 `.ai/sync.mjs` 派生为引用存根，指向 `.ai/RULES.md`），以及分层目录 `AGENTS.md`（`.ai/scopes/**` 派生）。Antigravity 原生按当前目录向上发现 `AGENTS.md` / `GEMINI.md`；本目录不重复派生 `.agents/rules/`，避免三份加载。
- **工作区技能（零重复直引）**：`skills.json` 通过 Antigravity 官方声明式配置 `entries: [{ "path": ".ai/skills" }]` 直接指向公共技能池 `.ai/skills/`——磁盘零重复、直接保留技能内 `scripts/` / `templates/` 等相对路径，新增技能自动生效，且避免与 Cursor 对 `.agents/skills/` 的兼容扫描重叠。
- **子代理与命令差异（vs Gemini CLI）**：Antigravity 使用内置子代理（`self` / `research` / `browser` / `image-generator`）及动态 `define_subagent`，不自动扫描 `.gemini/agents/*.md` 或 `.gemini/commands/*.toml`（后者服务于 Gemini CLI）。执行蜂群角色或命令时直接读取 `.ai/agents/<蜂群名>/<role>.md` 与 `.ai/commands/` 唯一源。
- **Hooks / MCP / Plugins**：Antigravity 支持 `.agents/hooks.json`、`.agents/mcp_config.json`、`.agents/plugins/`；**当前未配置**工具级源，需要时在 `.ai/tools/antigravity/` 建源后经 `node .ai/sync.mjs` 派生。仓库 git 钩子（`.ai/hooks/`）与环境连接（`.ai/env/`）属另一机制。
- 本说明位于 `.agents/` 根下而非 `rules/` / `skills/` 内，不会被当作规则或技能加载。

> 官方释义 vs 本仓库适配的完整差异矩阵见 ADR `2026-11-27-05-antigravity-official-alignment.md`。

<!-- 派生自 .ai/tools/antigravity/ANTIGRAVITY.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
