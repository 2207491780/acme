# .ai/skills/ — 技能公共池

**用途**：跨工具共享的「技能（Skill）」定义。技能 = 面向某类任务的成套操作指引。

## 文件格式

沿用 Claude Code 技能包结构（一个技能一个目录，内含 `SKILL.md`）：

```text
.ai/skills/<skill-name>/
├── SKILL.md      # frontmatter（name、description）+ 步骤化操作指引
└── ...           # 可选：参考文件、模板、脚本
```

## 说明

- 派生目标见 [../ARCHITECTURE.md](../ARCHITECTURE.md)「派生一览」（iFlow 以 workflow 形态承接技能目录）。
- 不支持技能目录的工具：以规则形式摘要并入其规则文件（摘要注明完整版位置，手工维护）。
- 本文件（SKILL-POOL.md）仅作源目录说明，**不会被派生**；只给单一工具用的技能放该工具自己的目录源（如 `.ai/tools/claude/skills/`）。
