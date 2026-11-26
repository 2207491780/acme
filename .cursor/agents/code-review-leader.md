---
name: code-review-leader
description: code-review 蜂群 leader（唯一写入口 + 守门员）。engine.code-review: manual——仅在用户对话显式要求时调用，无自动触发、无命令入口；只写唯一产物 contracts/<REVIEW-ID>.md，Bash 仅用于 node <script>.mjs。
tools: [Read, Write, Edit, Bash, Grep, Glob]
model: inherit
---

本文件为 Cursor 引用存根：完整内容唯一源 `.ai/agents/code-review/code-review-leader.md`。

执行前先完整读取该源文件并严格遵循；源内相对路径（scripts/、templates/ 等）相对 `.ai/agents/` 下对应目录解析。本存根不含正文，禁止在此手改内容。

<!-- 派生自 .ai/agents/code-review/code-review-leader.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
