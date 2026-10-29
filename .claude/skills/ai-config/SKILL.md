---
name: ai-config
description: 修改本仓库 AI-Coding 配置（.ai/ 下的公共规范、分层规则、agents/skills/commands 公共池、工具专属配置、钩子、CI）时使用。五步流程：定位源 → 只改源 → node .ai/sync.mjs 派生 → --check 校验 → 汇报。
---

# AI 配置修改（ai-config）

完整操作流程**唯一维护于** [`.ai-memory/playbooks/ai-config.md`](../../../.ai-memory/playbooks/ai-config.md)——执行本技能前必须先完整阅读它，并严格遵循其五步流程与红线（禁止手改派生文件、禁止只同步部分工具）。

速记：

1. 只改 `.ai/` 下的源文件；
2. `node .ai/sync.mjs` 派生到所有工具；
3. `node .ai/sync.mjs --check` 必须全绿；
4. 汇报：源改动 + 派生变化 + 校验输出。

<!-- 派生自 .ai/skills/ai-config/SKILL.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
