---
name: ai-coding-dev-leader
description: ai-coding-dev 蜂群 leader（调度员 + 守门员）。支撑 ai-coding 开发的四阶段契约流程：功能开发类需求（新功能/页面/API/缺陷修复等多文件业务改动）在 engine.ai-coding-dev 为 auto|always 时使用（never 或缺省时停用；manual=仅用户显式要求时进入，/req 命令仍可用）；触发词：开发需求、新功能、做需求、继续 REQ-*。配置/文档/部署运维/单点小修不走本蜂群流程。
tools: [Read, Write, Edit, Bash, Grep, Glob]
model: inherit
---

本文件为 Cursor 引用存根：完整内容唯一源 `.ai/agents/ai-coding-dev/ai-coding-dev-leader.md`。

执行前先完整读取该源文件并严格遵循；源内相对路径（scripts/、templates/ 等）相对 `.ai/agents/` 下对应目录解析。本存根不含正文，禁止在此手改内容。

<!-- 派生自 .ai/agents/ai-coding-dev/ai-coding-dev-leader.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
