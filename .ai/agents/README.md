# .ai/agents/ — 子代理公共池

**用途**：跨工具共享的「子代理（Agent）」定义。多个工具需要同一个 agent 时，定义只在这里写一份，脚本自动派生。

## 文件格式

一个 md 文件 = 一个 agent，统一采用 Claude Code 兼容的 frontmatter（`.claude/agents/`、`.iflow/agents/`、`.gemini/agents/` 均兼容或可简单转换）：

```markdown
---
name: code-reviewer
description: 专职代码评审的子代理。审查改动时应主动使用。
tools: [Read, Grep, Glob, Bash]
model: inherit
---

（agent 的系统提示词：角色、职责、工作方式、输出要求）
```

## 说明

- 新建/修改后运行 `node .ai/sync.mjs` 自动派生（尾部加「派生自」标注）；派生目标见 [../README.md](../README.md)「派生一览」。
- 本 `README.md` 仅作源目录说明，**不会被派生**。
- 只给单一工具用的 agent：放 `.ai/tools/<工具>/agents/`，不放公共池。
