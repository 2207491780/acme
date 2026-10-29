> ⚙️ 本目录为**生成产物**：所有 AI 配置统一维护于 [.ai/](../.ai/README.md)，由 [.ai/sync.mjs](../.ai/sync.mjs) 派生，请勿手改（本说明文件亦然）。

# .iflow/ — 心流 iFlow CLI

- 项目规范入口：根目录 `AGENTS.md` 与 `CLAUDE.md` 均为薄存根（引用 `.ai/RULES.md`，内容一致，任一读取方式都能覆盖）。
- `agents/`、`commands/`：来自 `.ai/agents|commands/` 公共池 + `.ai/tools/iflow/` 下的私有内容。
- `workflows/`：承接 `.ai/skills/` 公共池（iFlow 以 workflow 形态承载技能）。

<!-- 派生自 .ai/tools/iflow/README.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
