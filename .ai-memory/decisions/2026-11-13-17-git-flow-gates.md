# Git 收口门禁：main 归档、任务分支不推远端、镜像本地预检

日期：2026-11-13
补充：`2026-11-13-02-ai-git-governance.md`（授权分级不变）；取代「main 为主线」的隐含口径。

## 背景

2026-11-13 实测一次违规收口：AI 会话把 `feat/memory-consolidation` 推到双远端，合并后只推
`acme-foundation`、未镜像 `ai-coding-support`，按门禁逻辑 mirror-sync 必然判红。复盘出配置层缺口：

- 分支模型表把 `main` 当受保护主线，合流流程却以 `acme-foundation` 为主线；pre-push 只拦 `main`，
  真主线反而不设防，还得靠 `hashFiles` 守卫处理无应用代码的镜像分支。
- 「功能分支默认留本地」只有文字，pre-push 明确不拦分支推送/删除，CI 也不因远端多出 feat 分支报错。
- 「两策略分支必须同一条命令推送」只写在 `parallel-dev.md`，RULES 没有触发条款；镜像一致性只有事后
  CI 校验，本地无预检，违规代价是远端红一次。
- 「每个 AI 会话用独立工作树」无任何工具侧提示，会话默认落在主工作树。

## 决定

1. **主线唯一化**：`acme-foundation` 为受保护主线；`main` 归档为只读历史分支，pre-push 仍拒绝推送/
   强推/删除。
2. **任务分支默认不推远端**：pre-push 拒绝创建/更新远端 `feat/*`、`fix/*`；确需远端存档用
   `ACME_ALLOW_BRANCH_PUSH=1` 显式豁免。删除远端任务分支不拦（纠偏动作，权限仍归「授权分级」）。
3. **镜像本地预检**：新增 `.ai/hooks/mirror-check.mjs`，pre-push 在推送策略分支前按 CI mirror-sync
   同口径校验两分支 AI 路径逐字节一致，不一致直接拒推；策略分支不允许删除。
4. **重作业门禁改挂主线**：`ci.yml` 的 app-quality / backend-quality 由 `refs/heads/main` 改为
   `refs/heads/acme-foundation`，`main` 不再触发。
5. **规则层只留触发与结论**：合流流程点明「合并/推送前先读 parallel-dev.md」与同命令双推，
   细节仍留 playbook，避免 RULES 预算膨胀。

## 被否的选项

- **只改文档、不加门禁**：否决——默认策略靠自觉，本次违规就是这么发生的；同类问题必须由钩子兜底。
- **拦截 `acme-foundation` 直接推送**：否决——与三步流程的 ③「验证通过再推送」冲突，推送主线是交付
  路径；「不得在主线直接开发提交」只能由流程约束，无法由推送钩子判定。
- **拦截删除远端 feat/fix 分支**：否决——删除是纠偏动作，拦截只会诱导绕过钩子；删除权限已由
  授权分级（需明确指令）约束。
- **提高 `rulesBudgetBytes` 换篇幅**：否决——细节属 playbook，规则只该留结论（见 2026-11-13-01）。
- **引入 husky / lefthook 等钩子框架**：否决——现有 sh 钩子零依赖且已覆盖，不新增依赖。
