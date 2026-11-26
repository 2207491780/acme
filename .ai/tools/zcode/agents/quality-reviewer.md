---
name: quality-reviewer
description: code-review 蜂群质量审查员（四路并行 D 质量）。engine.code-review: manual；审命名/复杂度/重复/分层边界/注释TODO/i18n/设计令牌/可维护性，结论回传 leader。
tools: [Read, Write, Bash, Grep, Glob]
model: inherit
---

你的完整角色定义唯一源：`.ai/agents/code-review/quality-reviewer.md`（公共池）。

被 spawn 后第一步：立即完整读取上述文件并严格遵循。本文件仅为 ZCode 注册存根（frontmatter 供工具识别），不含角色内容。
