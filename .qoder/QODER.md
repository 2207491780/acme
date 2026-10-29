> ⚙️ 本目录为**生成产物**：所有 AI 配置统一维护于 [.ai/](../.ai/ARCHITECTURE.md)，由 [.ai/sync.mjs](../.ai/sync.mjs) 派生，请勿手改（本说明文件亦然）。

# .qoder/ — 阿里 Qoder

- 全局规范：`rules/project_rules.md` 为薄存根（引用 `.ai/RULES.md`），文件头带 `alwaysApply: true` frontmatter——Qoder 靠 frontmatter 决定激活方式，无 frontmatter 的规则**不会**每次会话常驻加载。Qoder 同时也自动读取根目录 `AGENTS.md`。
- Qoder 私有规则（如 spec 导向开发的流程约束）：源在 `.ai/tools/qoder/rules/`（用法见 `.ai/tools/TOOL-SOURCES.md`）。
- Qoder 无项目级 agents/skills/commands 目录，跨工具能力经规则间接约束。
- 本说明位于 `.qoder/` 根下而非 `rules/` 内，不会被当作规则加载。

<!-- 派生自 .ai/tools/qoder/QODER.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
