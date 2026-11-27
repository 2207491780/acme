# contracts/ — 契约驱动多代理工作流的契约目录

每个需求一份契约实例 `{REQ-ID}.md`，模板：`.ai/skills/team-leader/templates/contract-template.md`；流程宪法：`.ai-memory/playbooks/multi-agent.md`。

- **已入库（2026-11-20 起，补记 ADR 见 `.ai-memory/decisions/2026-11-27-04-contracts-committed-policy.md`）**：契约 draft 即提交、confirmed 后状态更新再提交——跨会话/跨机可见，需求号/迁移号占用记录随 git 防撞；契约禁存凭证（安全红线不变）。code-review 蜂群的唯一入库产物 `REVIEW-*.md` 亦落本目录。
- 在**主工作树**持有；并行工作树内经 `../../contracts/` 跨树读取。
- 需求 DONE 后原地保留；「流程状态」节是唯一现场，任何新会话读该节即可接管（取舍见 `.ai-memory/decisions/2026-11-14-19-multi-agent-contract-workflow.md`）。
