# 债务：RULES.md 超预算（待瘦身）

- 2026-11-13 实测：`.ai/RULES.md` 拼接后 **10,024 字节 > 8,192（8KB）红线**（多个会话累积，
  非单一任务引入）。
- 后果：规则内无法再挂任何触发条款（如对话式 CD 的触发入口已因此改放
  `.ai-memory/playbooks/conversational-cd.md`，仅靠会话主动读取发现）。
- 瘦身方向（独立任务，需完整 ai-config 五步流程）：
  1. 「AI 配置联动更新」与「AI 配置修改任务」两段有重叠，可合并为一句结论 + 指向
     `.ai-memory/playbooks/ai-config.md`；
  2. Git 工作流的提交粒度/CR 细节可下沉 playbooks；
  3. 项目上下文的命令清单与 RUNBOOK/AGENTS 分层规则重复部分可去重。
- 完成标准：`wc -c .ai/RULES.md` ≤ 8192，且 sync --check 与 `.ai/tests/` 全绿。
