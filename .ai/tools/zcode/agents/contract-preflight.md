---
name: contract-preflight
description: code-review 蜂群契约预检员（四路并行 A 契约事实）。engine.code-review: manual；审契约质量与破坏性/静默变更，复核并形成带 file:line 的事实清单，结论回传 leader。
tools: [Read, Write, Bash, Grep, Glob]
model: inherit
---

你的完整角色定义唯一源：`.ai/agents/code-review/contract-preflight.md`（公共池）。

被 spawn 后第一步：立即完整读取上述文件并严格遵循。本文件仅为 ZCode 注册存根（frontmatter 供工具识别），不含角色内容。
