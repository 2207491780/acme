# ACME UI 原型设计区

UI 原型（页面 / 屏幕 / 演示数据）的独立设计区，与前端工程 `workspace/apps/frontend/` 物理隔离。
根 `AGENTS.md`（由 `.ai/RULES.md` 派生）始终生效，本文件只做原型区补充。

## 约定

- **原型迭代只在本目录进行**：不把本区页面/数据接进前端工程源码；恢复可运行预览是独立任务，
  接线方式存档于 `docs/frontend-wiring.patch`。
- 来源与目录映射见同目录 `UI-PROTOTYPE.md`（自已删除的 `prototype/ui` 分支 `82e1493` 逐字收编）；
  设计决策见 `docs/decisions/`（编号沿用原分支 02–07）。
- 演示数据保持确定性生成（同输入同输出，可测试）；不含真实凭证（密钥仅脱敏形态）。
- 原型成熟化时按 `docs/decisions/2026-11-12-02-prototype-zone.md` 迁移：函数迁入前端正式业务域，
  其余随原型演进；迁移属独立任务，须任务明确要求。

<!-- 派生自 .ai/scopes/workspace/ui-prototype/AGENTS.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
