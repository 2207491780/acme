# 基座能力增强计划——执行现场（工作记忆，随时改写）

> 权威锚点：规划 `docs/platform-enhancement-plan-2026-11-21.md` ｜ 任务规格：`docs/bpm-engine-design-2026-11-21.md`、`docs/scheduler-center-design-2026-11-21.md` ｜ 每任务现场：`contracts/REQ-*.md`「流程状态」节
> 执行模式：用户 2026-11-21 批量授权——按规划自动执行免逐项确认；每项测试通过→合并→双分支同命令推送；token 不设限、禁止偷懒。

## 状态总览（TL 每次阶段迁移后更新本节）

| 轮 | 任务 | 状态 | REQ/产物 |
|---|---|---|---|
| 0 | 开工检查+收尾提交+镜像推送 | DONE | 23a74f0/e504207/7ba80ee 已推 |
| 1 | BPM-0 预研定稿 | DONE | docs/bpm-engine-design-2026-11-21.md + ADR-03 |
| 1 | JOB-0 预研定稿 | DONE | docs/scheduler-center-design-2026-11-21.md + ADR-04 |
| 1 | THEME-1 偏好引擎+抽屉一期 | III-GATE（round 1 已收口：Option B 投影裁决落地、三包 typecheck 绿、定向 201 全绿；全量门禁后台跑 exec_7e386777；ui:guide 已重生成） | REQ-20261121-010725 |
| 1 | TENANT-1 租户套餐模型 | DONE（V24 落地，门禁五项 PASS，2dc7eb2 已合并待推） | REQ-20261121-022910 |
| 2 | THEME-2 外观能力包 | DONE（v3 落地，门禁 PASS，已合并待推） | REQ-20261121-043502 |
| 2 | TENANT-2 功能分配-服务能力开关 | DONE（V25 落地，门禁五项 PASS，92c4f88 已合并待推） | REQ-20261121-054613 |
| 2 | JOB-1 调度中心核心 | DONE（c553b7a 已合并待推；终验全 reactor EXIT=0；水位 77；UTC 锁表纪律+坑 16 入册） | REQ-20261121-071002 |
| 2 | BPM-1 引擎内核 MVP | round 1 修复中（backend 154 文件交付+真实栈冒烟；tester 48 例 21/48+坑 16 六文件绿；27 红归因 9 簇实现缺陷修复中——协议终稿三节已钉死进契约） | REQ-20261121-142316 |
| 3+ | THEME-3 → JOB-2 → BPM-2 → JOB-3/BPM-3/TENANT-3 → BPM-4/JOB-4/TENANT-4 → BPM-5/TENANT-5/THEME-4 → BPM-6/JOB-5 | 待启动 | |

## 关键执行事实（跨任务复用，勿重推导）

- 迁移号水位：platform-system 已用至 V23（TENANT-1 预占 V24/V25…，按取号顺序写死 frontmatter）；workflow/scheduler 各自迁移空间自 V1。
- 端口分配：workflow=9883、scheduler=9884；路由 /admin/v1/workflow/**、/admin/v1/scheduler/**。
- 提交纪律：pathspec 提交（git commit -- path）会被 pre-commit 临时索引坑（已暂存的 AI 文件被当未跟踪）——一律全索引顺序提交；ADR 等未跟踪 AI 文件必须先 add 否则任何提交都被拒。
- 推送纪律：推前在镜像 worktree（.worktrees/sync-ai-mirror-20261117）`git checkout acme-foundation -- .ai-memory`（或其他 AI 路径）→ commit → 主树 `node .ai/hooks/mirror-check.mjs` → `git push origin acme-foundation ai-coding-support` 同命令推双远端。
- 契约机器层门禁：`node .ai/skills/team-leader/scripts/check-contract.mjs contracts/{REQ-ID}.js`；五门禁清单见 multi-agent.md。
- 后端 Maven 不在 PATH（见 flex-test-assembly.md 构建与运行节，backend-dev 知道）；前端 pnpm 在 workspace/apps/frontend。
