# .ai/commands/ — 自定义命令公共池

**用途**：跨工具共享的「斜杠命令」定义。一个 md 文件 = 一条命令（文件名即命令名，`deploy.md` → `/deploy`）。

## 文件格式

```markdown
---
description: 执行项目部署
allowed-tools: [Bash(npm:*)]
---

（命令提示词：参数、步骤、输出要求，支持 $ARGUMENTS 占位符）
```

## 说明

- 派生目标见 [../ARCHITECTURE.md](../ARCHITECTURE.md)「派生一览」。
- ⚠️ Gemini 命令是 **TOML** 格式：公共池不自动转换，需手工转换后放 `.ai/tools/gemini/commands/`。
- 本文件（COMMAND-POOL.md）仅作源目录说明，**不会被派生**。
