# 基座能力增强计划——执行现场（工作记忆，随时改写）

> 权威锚点：规划 `docs/platform-enhancement-plan-2026-11-21.md` ｜ 任务规格：`docs/bpm-engine-design-2026-11-21.md`、`docs/scheduler-center-design-2026-11-21.md` ｜ 每任务现场：`contracts/REQ-*.md`「流程状态」节
> 执行模式：用户 2026-11-21 批量授权——按规划自动执行免逐项确认；每项测试通过→合并→双分支同命令推送；token 不设限、禁止偷懒。

## 状态总览（TL 每次阶段迁移后更新本节）

| 轮 | 任务 | 状态 | REQ/产物 |
|---|---|---|---|
| 0 | 开工检查+收尾提交+镜像推送 | DONE | 23a74f0/e504207/7ba80ee 已推 |
| 1 | BPM-0 预研定稿 | DONE | docs/bpm-engine-design-2026-11-21.md + ADR-03 |
| 1 | JOB-0 预研定稿 | DONE | docs/scheduler-center-design-2026-11-21.md + ADR-04 |
| 1 | THEME-1 偏好引擎+抽屉一期 | DONE（8e8b0c7 已合并；门禁五项台账见 REQ-010725；随 2026-11-21 22cc9f6 推送双远端） | REQ-20261121-010725 |
| 1 | TENANT-1 租户套餐模型 | DONE（V24 落地，门禁五项 PASS，2dc7eb2 已合并待推） | REQ-20261121-022910 |
| 2 | THEME-2 外观能力包 | DONE（v3 落地，门禁 PASS，已合并待推） | REQ-20261121-043502 |
| 2 | TENANT-2 功能分配-服务能力开关 | DONE（V25 落地，门禁五项 PASS，92c4f88 已合并待推） | REQ-20261121-054613 |
| 2 | JOB-1 调度中心核心 | DONE（c553b7a 已合并待推；终验全 reactor EXIT=0；水位 77；UTC 锁表纪律+坑 16 入册） | REQ-20261121-071002 |
| 2 | BPM-1 引擎内核 MVP | DONE（round 1 收口：workflow 49/49+bootstrap 冒烟 6/6+全 reactor 51/52 定向修复；合并 4f90fba 快进+合并树冒烟复跑绿；已推双远端；工作树/分支回收；裁决⑨a-⑭入契约） | REQ-20261121-142316 |
| 3+ | JOB-2 执行器 SDK | **DONE**（全 reactor 49 模块 BUILD SUCCESS 零失败；合并 ffe27b9 快进+合并树定向复验绿+双远端推送+工作树/分支回收；交付裁决四项入契约；JOB-3 钉死=4 行启停运维动作） | REQ-20261121-200724 |
| 3+ | THEME-3 偏好持久化+mobile 对齐 | **DONE**（合并 085791b 快进+合并树 IT 17/17 复验绿+双远端推送+工作树/分支回收；门禁链 GATE_ALL_PASS：三包 typecheck/core 28/mobile 14/web 67 全绿/build/desktop check；后端模块 402/402；tester 46 用例+三轮适配闭环） | REQ-20261121-200415 |
| 3+ | JOB-3 调度中心管理页 | **DONE**（合并 95e5eaa 快进+合并树 V28 复验 exit=0+双远端推送+工作树/分支回收；门禁 GATE_ALL_PASS：web 69 文件/mobile 14/build/desktop ✓；51 例映射 AC-1~12；并行 flake 串行判别绿；交付裁决入契约） | REQ-20261122-023213 |
| 3+ | BPM-2 流程设计器 | **DONE**（合并 4aa192c（amend 修正）+合并树复验 typecheck/定向绿+双远端推送+分支回收；门禁 GATE_ALL_PASS web 70 文件/build 18.25s/desktop ✓；tester 50/50+校验规则三裁决；浏览器实测走查 PASS 截图取证 .ai-tmp/bpm2-designer-evidence.png；与 JOB-3 并行合流 TL 手解三文件冲突+合并树复验兜底） | REQ-20261122-023441 |
| 3 | 轮 3 全收口（THEME-3/JOB-2/BPM-2+JOB-3 提前） | **DONE** | |
| 遗留 | 孤儿目录人工清理 | .worktrees 下 feat-req-20261120-213603-menu-filter、feat-req-20261121-071002-scheduler-core、feat-req-20261122-023441-bpm-designer 三个孤儿残留目录（Safe-Delete 钩子拒绝脚本删除，注册已注销、内容可弃）——需用户人工删除或授权 bypass | — |
| 4 | BPM-3 流程管理页 | **DONE**（合并 cd7033a+合并树复验 typecheck/定向绿+双远端推送+回收；门禁 GATE_ALL_PASS web **74 文件全绿**/build 52.99s/desktop ✓；backend 58/58+V29 3/3；tester 46 例映射 AC-1~12；浏览器实测=只读画布 svg 1/nodes 4/edges 3+五块 Drawer+待办两子区；运行时重启一次四路续任无损） | REQ-20261122-102730 |
| 4 | TENANT-3 子租户层级 | **DONE**（合并 1fc9076+合并树 V30 复验 exit=0+双远端推送+回收；backend 414/414 Skipped=0+层级 9/9 两路独立复验；frontend 9/9+typecheck 绿；tester 19 例映射；§6.3 三问推荐答案钉死；门禁注记=唯一红为已知负载敏感用例零代码交集放行） | REQ-20261122-102954 |
| 4 | 轮 4 全收口（BPM-3+TENANT-3） | **DONE** | |
| 5 | BPM-4 审批详情深化 | II-DEV 双路（REQ-20261122-150750 契约 confirmed：Drawer 升级五块/complete 形状修正修现网 comment 丢弃缺口/驳回候选剔 delegate 污染+含 StartEvent/delegate 仅 CLAIMED/schema 联动归二期/加签减签排除；纯前端后端零变更） | REQ-20261122-150750 |
| 5 | JOB-4 调度韧性 | II-DEV 双路（REQ-20261122-150756 契约 confirmed：重试调度侧唯一+retry_policy 向后兼容扩展/分片真扇出 N 次下发+V3 索引改造/告警 system 内部端点+三类触发+5 分钟去重/超时归一回调开放 TIMEOUT/可观测复用现有面） | REQ-20261122-150756 |
| 5 | TENANT-4 生命周期+配额 | II-DEV 三路（REQ-20261122-150802 契约 confirmed：双态保留+grace_until 派生四视觉态（否决物化）/配额三级覆盖 租户覆盖表>套餐列>平台缺省 一期仅存储/usage 实时聚合/切换视角不改造/过期派生无定时） | REQ-20261122-150802 |
| 6+ | BPM-5/TENANT-5/THEME-4 → BPM-6/JOB-5 | 待启动 | |

## 关键执行事实（跨任务复用，勿重推导）

- 迁移号水位：platform-system 已用至 V23（TENANT-1 预占 V24/V25…，按取号顺序写死 frontmatter）；workflow/scheduler 各自迁移空间自 V1。
- 端口分配：workflow=9883、scheduler=9884；路由 /admin/v1/workflow/**、/admin/v1/scheduler/**。
- 提交纪律：pathspec 提交（git commit -- path）会被 pre-commit 临时索引坑（已暂存的 AI 文件被当未跟踪）——一律全索引顺序提交；ADR 等未跟踪 AI 文件必须先 add 否则任何提交都被拒。
- 推送纪律：推前在镜像 worktree（.worktrees/sync-ai-mirror-20261117）`git checkout acme-foundation -- .ai-memory`（或其他 AI 路径）→ commit → 主树 `node .ai/hooks/mirror-check.mjs` → `git push origin acme-foundation ai-coding-support` 同命令推双远端。
- 契约机器层门禁：`node .ai/skills/team-leader/scripts/check-contract.mjs contracts/{REQ-ID}.js`；五门禁清单见 multi-agent.md。
- 后端 Maven 不在 PATH（见 flex-test-assembly.md 构建与运行节，backend-dev 知道）；前端 pnpm 在 workspace/apps/frontend。
