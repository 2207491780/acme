# 债务（已解决）：RULES.md 预算口径与瘦身

- 背景：多会话累积致 RULES.md 膨胀；上限已由 ADR `2026-11-13-01-rules-budget.md` 从 8KB 放宽至 10KB（`rulesBudgetBytes: 10240`）。本草稿旧版误以 8192 为红线，已纠正。
- 本次处理（2026-11-13，fix/ai-config-context-budget）：
  1. 合并 `ai-guidelines.md`「AI 配置联动更新」+「AI 配置修改任务」为一段结论 + playbook 指针（红线全保留）；
  2. `project.md` 刷新为全栈实况并与分层规则去重；
  3. 陈旧「≤8KB」口径统一改为指向引擎开关（`ai-guidelines.md` / `ai-coding-config.yml` 注释）。
- 现状：RULES.md 拼接后 < 10240B；字节/余量以 `node .ai/sync.mjs --check` 实测为准，不再硬编码免再漂移。
- 后续：若再逼近上限，优先继续瘦身（Git 提交粒度/CR 细节下沉 playbooks）而非再放宽。