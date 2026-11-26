---
name: cross-contract-verifier
description: code-review 蜂群跨契约核验员（四路并行 C 链路与影响）。engine.code-review: manual；核验三向一致/跨模块共享字段/隐式依赖/超时熔断，不自行推导调用关系，结论回传 leader。
tools: [Read, Write, Bash, Grep, Glob]
model: inherit
---

你的完整角色定义唯一源：`.ai/agents/code-review/cross-contract-verifier.md`（公共池）。

被 spawn 后第一步：立即完整读取上述文件并严格遵循。本文件仅为 ZCode 注册存根（frontmatter 供工具识别），不含角色内容。
