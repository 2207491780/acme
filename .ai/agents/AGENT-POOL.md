# .ai/agents/ — 子代理蜂群（swarm）公共池

**用途**：跨工具共享的「子代理（Agent）」定义。多个工具需要同一个 agent 时，定义只在这里写一份，脚本自动派生。

## 目录约定（蜂群文件夹）

- `.ai/agents/` 下**除本说明文件外，只允许放文件夹**；每个文件夹代表支撑一个功能的 agent 蜂群。
- 当前蜂群：`ai-coding-dev/`（支撑 ai-coding 开发），内含现有五个角色 `ai-config-editor.md` / `backend-dev.md` / `diagnostic.md` / `frontend-dev.md` / `tester.md`，以及 leader `ai-coding-dev-leader.md`。
- 每个蜂群**必须有一个 leader** 负责核心调度，命名为 `<蜂群名>-leader.md`（如 `ai-coding-dev/ai-coding-dev-leader.md`，必须是普通文件、不跟随符号链接），避免未来多蜂群重名。
- 上述两条约束（根级只允许说明文件 + 文件夹、每蜂群必须有 `<蜂群名>-leader.md`）由 `node .ai/sync.mjs` 强制校验（在配置加载前执行，错误信息带具体路径），不靠人工约定；违规时任何派生/校验都会中止。
- **蜂群用途描述只登记在本文件**（如 `ai-coding-dev/` 支撑 ai-coding 开发）；入口触发段只说流程、leader 路径与手册，不再写蜂群专属用途声明，避免新增蜂群时被误标用途。
- 蜂群触发模式配置在**全局唯一源** `.ai/ai-coding-config.yml` 的 `engine` 段：key = 蜂群名（如 `ai-coding-dev`），value = `auto|always|never`；缺省视为停用且不注入触发段。不要在蜂群文件夹内另建触发配置文件。
- 派生时**扁平输出**：源允许 `.ai/agents/<蜂群名>/<agent>.md`，各工具目录仍为 `<工具>/agents/<agent>.md`（保持工具原生发现能力）；溯源尾注保留完整源路径。

## 文件格式

一个 md 文件 = 一个 agent，统一采用 Claude Code 兼容的 frontmatter（`.claude/agents/`、`.gemini/agents/` 均兼容或可简单转换）。Gemini 的工具名体系不同：引擎派生到 `.gemini/agents/` 时自动把 frontmatter 的 `tools` 映射为 Gemini 内置名（Read→read_file、Bash→run_shell_command 等）并移除 `model: inherit`（Gemini 缺省即继承，见 ADR 2026-11-14-24），池内源文件无需关心目标差异：

```markdown
---
name: code-reviewer
description: 专职代码评审的子代理。审查改动时应主动使用。
tools: [Read, Grep, Glob, Bash]
model: inherit
---

（agent 的系统提示词：角色、职责、工作方式、输出要求）
```

示例源路径：`.ai/agents/ai-coding-dev/code-reviewer.md` → 派生为 `.claude/agents/code-reviewer.md`。

## 说明

- 新建/修改后运行 `node .ai/sync.mjs` 自动派生（尾部加「派生自」标注）；派生目标见 [../ARCHITECTURE.md](../ARCHITECTURE.md)「派生一览」。
- 本文件（AGENT-POOL.md）是**唯一保留的说明文件**，仅作源目录说明，**不会被派生**；其所在目录下不放其他散落 md。
- 只给单一工具用的 agent：放 `.ai/tools/<工具>/agents/`，不放公共池。
