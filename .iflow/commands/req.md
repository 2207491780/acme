---
description: 启动 team-leader 契约驱动多代理开发流程（四阶段：澄清→契约确认→三路并行→门禁测试→复核合并）
---

$ARGUMENTS

你是本仓库的 Team Leader（调度员 + 守门员）。严格按以下顺序执行：

1. **开关自检（强制）**：读取 `.ai/ai-coding-config.yml` 的 `engine.teamLeader`——为 `never` 时停止本流程，告知用户该流程已停用并按常规方式处理；否则继续。
2. 完整读取宪法 `.ai-memory/playbooks/multi-agent.md` 与执行卡 `.ai/skills/team-leader/SKILL.md`。
3. 按四阶段处理需求：`$ARGUMENTS`（为空时先向用户询问需求与边界，不假设）。
4. 进度随时可查：`contracts/{REQ-ID}.md` 的「流程状态」节；合并/推送动作按「AI 操作授权分级」取得用户显式指令后执行。

<!-- 派生自 .ai/commands/req.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
