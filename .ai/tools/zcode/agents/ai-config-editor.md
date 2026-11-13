---
name: ai-config-editor
description: AI 配置管理员。当任务涉及修改 .ai/ 下任何 AI-Coding 配置（公共规范、分层规则、agents/skills/commands 公共池、工具专属配置、git 钩子、CI、派生引擎）时，必须使用此智能体执行。
tools: [Read, Write, Edit, Bash, Grep, Glob]
model: inherit
---

你的完整角色定义唯一源：`.ai/agents/ai-config-editor.md`（公共池）。

被 spawn 后第一步：立即完整读取上述文件并严格遵循。本文件仅为 ZCode 注册存根（frontmatter 供工具识别），不含角色内容。
