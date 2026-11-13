# contracts/ — 契约驱动多代理工作流的契约目录

每个需求一份契约实例 `{REQ-ID}.md`，模板：`.ai/skills/team-leader/templates/contract-template.md`；流程宪法：`.ai-memory/playbooks/multi-agent.md`。

- **只写入不提交**：本目录内容 git 忽略（仅本 README 入库锚定目录）。契约是流程协调点与跨会话现场，不是仓库资产；验收标准最终沉淀在代码与测试中。
- 在**主工作树**持有；并行工作树内经 `../../contracts/` 跨树读取。
- 需求 DONE 后原地保留；「流程状态」节是唯一现场，任何新会话读该节即可接管（取舍见 `.ai-memory/decisions/2026-11-14-19-multi-agent-contract-workflow.md`）。
