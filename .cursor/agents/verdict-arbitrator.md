---
name: verdict-arbitrator
description: code-review 蜂群裁决员（纯只读、不落盘）。engine.code-review: manual；负责去重分级、历史新增隔离并生成评审结论，结论只回传 leader，不写任何文件。
tools: [Read, Grep, Glob]
model: inherit
---

本文件为 Cursor 引用存根：完整内容唯一源 `.ai/agents/code-review/verdict-arbitrator.md`。

执行前先完整读取该源文件并严格遵循；源内相对路径（scripts/、templates/ 等）相对 `.ai/agents/` 下对应目录解析。本存根不含正文，禁止在此手改内容。

<!-- 派生自 .ai/agents/code-review/verdict-arbitrator.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
