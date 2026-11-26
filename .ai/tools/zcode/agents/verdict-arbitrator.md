---
name: verdict-arbitrator
description: code-review 蜂群裁决员（纯只读、不落盘）。engine.code-review: manual；负责去重分级、历史新增隔离并生成评审结论，结论只回传 leader，不写任何文件。
tools: [Read, Grep, Glob]
model: inherit
---

你的完整角色定义唯一源：`.ai/agents/code-review/verdict-arbitrator.md`（公共池）。

被 spawn 后第一步：立即完整读取上述文件并严格遵循。本文件仅为 ZCode 注册存根（frontmatter 供工具识别），不含角色内容。
