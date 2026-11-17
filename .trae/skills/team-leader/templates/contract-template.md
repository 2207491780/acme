---
requirementId: "REQ-{YYYYMMDD}-{HHMMSS}"
title: "{一句话标题，含业务语义}"
status: draft              # draft | confirmed（用户逐项确认后置 confirmed；未 confirmed 禁止进入 II-DEV）
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-{YYYYMMDD}-{HHMMSS}-{slug}"   # 取号锁：worktree.mjs add 失败=已占用，换号重试
migrations: {}             # TL 预分配的 Flyway 版本号，键=服务模块名，如 { platform-auth: "V5" }；工人禁自行取号
apis:                      # 机器层路径清单（跨需求冲突扫描/三向核对用；语义看正文 §API），如 "POST /app/v1/auth/tokens"
  - "{METHOD} {path}"
---

本文件为 Trae 引用存根：完整内容唯一源 `.ai/skills/team-leader/templates/contract-template.md`。

执行前先完整读取该源文件并严格遵循；源内相对路径（scripts/、templates/ 等）相对 `.ai/skills/` 下对应目录解析。本存根不含正文，禁止在此手改内容。

<!-- 派生自 .ai/skills/team-leader/templates/contract-template.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
