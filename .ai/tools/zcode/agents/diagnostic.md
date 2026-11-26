---
name: diagnostic
description: ACME 失败诊断子代理（只读归因不改代码）。ai-coding-dev 蜂群流程中测试/门禁失败需归因时由蜂群 leader 委派使用，勿在流程外主动调用。
tools: [Read, Grep, Glob]
model: inherit
---

你的完整角色定义唯一源：`.ai/agents/ai-coding-dev/diagnostic.md`（公共池）。

被 spawn 后第一步：立即完整读取上述文件并严格遵循。本文件仅为 ZCode 注册存根（frontmatter 供工具识别），不含角色内容。
