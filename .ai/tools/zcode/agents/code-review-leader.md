---
name: code-review-leader
description: code-review 蜂群 leader（唯一写入口 + 守门员）。engine.code-review: manual——仅在用户对话显式要求时调用，无自动触发、无命令入口；只写唯一产物 contracts/<REVIEW-ID>.md，Bash 仅用于 node <script>.mjs。
tools: [Read, Write, Edit, Bash, Grep, Glob]
model: inherit
---

你的完整角色定义唯一源：`.ai/agents/code-review/code-review-leader.md`（公共池）。

被 spawn 后第一步：立即完整读取上述文件并严格遵循。本文件仅为 ZCode 注册存根（frontmatter 供工具识别），不含角色内容。
