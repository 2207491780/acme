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
| 遗留 | 孤儿目录人工清理 | ~~menu-filter/scheduler-core/bpm-designer 三孤儿目录~~（2026-11-23 用户授权 bypass 后删除完毕，git worktree list 已无残留注册） | — |
| 遗留 | 已合并本地分支删除待指令 | ~~JOB-4/TENANT-4/TENANT-5 三分支~~（2026-11-23 用户已授权删除，tip 均为合并点零丢失）；BPM-5（233425）/THEME-4（233427）合并后同口径待删 | — |
| 4 | BPM-3 流程管理页 | **DONE**（合并 cd7033a+合并树复验 typecheck/定向绿+双远端推送+回收；门禁 GATE_ALL_PASS web **74 文件全绿**/build 52.99s/desktop ✓；backend 58/58+V29 3/3；tester 46 例映射 AC-1~12；浏览器实测=只读画布 svg 1/nodes 4/edges 3+五块 Drawer+待办两子区；运行时重启一次四路续任无损） | REQ-20261122-102730 |
| 4 | TENANT-3 子租户层级 | **DONE**（合并 1fc9076+合并树 V30 复验 exit=0+双远端推送+回收；backend 414/414 Skipped=0+层级 9/9 两路独立复验；frontend 9/9+typecheck 绿；tester 19 例映射；§6.3 三问推荐答案钉死；门禁注记=唯一红为已知负载敏感用例零代码交集放行） | REQ-20261122-102954 |
| 4 | 轮 4 全收口（BPM-3+TENANT-3） | **DONE** | |
| 5 | BPM-4 审批详情深化 | **DONE**（合并 6aa33bd 快进+合并树复验 typecheck/定向 3 文件绿+双远端推送+回收；门禁 GATE_ALL_PASS web **78 文件全绿**/build 21.96s/desktop ✓；tester 20 例+frontend 适配 BPM-3 四例锚点；浏览器走查=审批 Drawer 五块+驳回回流时间线+X6 svg/nodes/edges 实证；运行时重启×3 四任续任） | REQ-20261122-150750 |
| 5 | JOB-4 调度韧性 | **DONE**（ZCode 会话续任收口：合并 c082d5e 快进+合并树定向复验绿+双远端推送+工作树回收；门禁 GATE_ALL_PASS：全 reactor 两段全绿（首跑至 infra 前+resume scheduler 04:34/workflow/bootstrap/business）+CoreIT 先行 3 连跑 17×3；REQ 16 例全绿；打回 round1=JOB-1 CoreIT 夹具竞态（diagnostic 归因非实现回归→A2 水位口径用例修复）；告警终裁仅 sys_message 落地） | REQ-20261122-150756 |
| 5 | TENANT-4 生命周期+配额 | **DONE**（ZCode 会话续任收口：合并 9733200 快进（rebase 冲突 4 文件=两侧同值注释措辞，取主线 f14f80f 版零损失）+合并树定向复验绿+post-rebase 定向绿；门禁 GATE_ALL_PASS：后端单次全量 reactor 0 失败（REQ IT 7/7+TT 4/4+infra 闭环 2/2+FileUseCaseTest 41/41+单体冒烟 6/6）+前端 typecheck/build/desktop 绿+core 321+mobile 102+web 637/639（唯 2 红=JOB-3 已知并行 flake 串行 19/19 判别绿披露）；打回 2 轮=①infra @EnableFeignClients 漏登记 TenantQuotaClient（22 上下文全灭）+bootstrap LocalTenantQuotaClient 适配器②tester TS 类型 5 错；AC-3 infra 闭环腿 AMB-3 关闭） | REQ-20261122-150802 |
| 5 | 并发队列 | 全部关闭（BPM-4/JOB-4/TENANT-4 成员随轮收口；TL 串行 mvn 纪律全程执行：J4 三连跑→J4 resume→T4 定向→T4 全量→双树复验） | — |
| 6 | BPM-5 集成 SDK+试点 | **DONE**（ZCode 会话续任收口：合并 f759e9f 快进+合并树复验绿+双远端推送+回收；门禁=两段式全 reactor+引擎 48/48+E2E 2/2 真跑；**打回四轮**：①测试域 yml 键面缺失（handler 注册键错→404=HANDLER_NOT_FOUND+事件 topic 错位零消费）→补键+收集器 WebServerInitializedEvent 稳态重登记 ②回调基址 @ConfigurationProperties 绑定期烘焙 :0→Environment 惰性解析 ③i18n SYS-13 孤儿键→allCodes 登记 ④HR 备案 600ms 定时 vs Kafka 冷启动消费竞态→A1 有界等待（修复前三测三点绿红绿实证）；偏差十项裁决入册；交付=framework-workflow 新模块+internal 3 端点+Kafka 事件流+business-leave 请假试点全链） | REQ-20261122-233425 |
| 6 | TENANT-5 开通模板+运营视图 | **DONE**（ZCode 会话续任收口：合并 55ecd04 快进+合并树复验绿+双远端推送+回收；后端全 reactor 0 失败+前端四项绿；V32 模板表+权限种子挂靠既有菜单保 55/33 水位+init 快照语义+usage-overview；AMB 六项裁决；水位适配独立 commit 披露制） | REQ-20261122-233426 |
| 6 | THEME-4 布局/页签/半深色/widget | **DONE**（ZCode 会话续任收口：合并 5eaadee 快进+合并树复验 45/45 绿+双远端推送+回收；backend reactor r3 全绿+前端四项绿+预算放宽 6 文件 65 例两轮复验+browser-use 五路径走查 PASS 11 图取证（三布局热切换/持久化水合/卡片页签/半深色/widget 显隐恢复全显）；schema v4+dnd-kit 入列；前端回归批三轮归因（负载说→闲置复跑推翻→主树对照 1.7x 慢相→预算边际+僵尸级联）） | REQ-20261122-233427 |
| 6 | 轮 6 全收口（BPM-5+TENANT-5+THEME-4） | **DONE**（三分支已删、工作树全回收、台账回写） | |
| 遗留 | 孤儿目录人工清理 | ~~三孤儿目录~~（已删）；2026-11-23 用户授权 bypass 后全部清理完毕 | — |

## 关键执行事实（跨任务复用，勿重推导）

- 迁移号水位：platform-system 已用至 V23（TENANT-1 预占 V24/V25…，按取号顺序写死 frontmatter）；workflow/scheduler 各自迁移空间自 V1。
- 端口分配：workflow=9883、scheduler=9884；路由 /admin/v1/workflow/**、/admin/v1/scheduler/**。
- 提交纪律：pathspec 提交（git commit -- path）会被 pre-commit 临时索引坑（已暂存的 AI 文件被当未跟踪）——一律全索引顺序提交；ADR 等未跟踪 AI 文件必须先 add 否则任何提交都被拒。
- 推送纪律：推前在镜像 worktree（.worktrees/sync-ai-mirror-20261117）`git checkout acme-foundation -- .ai-memory`（或其他 AI 路径）→ commit → 主树 `node .ai/hooks/mirror-check.mjs` → `git push origin acme-foundation ai-coding-support` 同命令推双远端。
- 契约机器层门禁：`node .ai/skills/team-leader/scripts/check-contract.mjs contracts/{REQ-ID}.js`；五门禁清单见 multi-agent.md。
- 后端 Maven 不在 PATH（见 flex-test-assembly.md 构建与运行节，backend-dev 知道）；前端 pnpm 在 workspace/apps/frontend。
- **-rf 坑④变体（轮 5 实证）**：`mvn test -rf :模块` 切断 reactor 后，上游依赖从本地仓库解析——上游含本轮新增类（如新 Feign client）时本地旧 jar 会引爆 NoClassDefFoundError（JOB-2 坑④同根）。定向自验一律 `-am`（既有口径）；resume 补跑时若上游有新增类，改单次全量 reactor 或先让上游 `-am` 跑过。
- **vitest 并行负载 flake 判别口径沿用**：重 jsdom 文件（如 schedulerAdminPage）在全量并行下 25-30s 超时假红，串行 `--no-file-parallelism` 判别绿=非回归（轮 3 JOB-3/轮 5 TENANT-4 两度实证），披露放行不进打回循环。
- **THEME-4 前端超时三课（2026-11-23，轮 6）**：①「串行判别」必须升级为「**闲置**串行判别」——串行只消 vitest 扇出、不消机器负载，判别时 TL 自身后台任务即负载源（A/B 对照：主树同用例 34s vs 历史基线 20.3s=1.7x 慢相）；②重 jsdom 交互用例超时会产生**僵尸窗口级联污染**：超时不中止 async fn，僵尸续跑写入跨用例残留（customAccent 例隔离单测 9s 绿实证）——同文件多例红先隔离单测判别再定罪；③预算放宽口径=max(实测×2.5, 现预算×2) 且重交互 ≥60s、逐文件披露（appearanceDrawer 20→45s 先例延续）；文件内**单例超时实参**要随文件级 setConfig 同步放宽（preferencesDrawer :542 30s 卡点）。
- **多代理并发 Kafka 宿主端口互斥（轮 6）**：Testcontainers 固定宿主端口（9092）的 IT 与其他 Kafka IT/全 reactor 并行会互踢致 AdminClient 空集群死循环挂起——Kafka 用户严格串行，全 reactor 不与任何容器 IT 并行。
- **@ConfigurationProperties 占位符烘焙坑（轮 6 BPM-5）**：绑定早于 Web 启动，`${local.server.port:${server.port}}` 在绑定期即被消化成字面 `:0`（RANDOM_PORT）且 late-resolve 无占位符可救——跨服务基址类配置一律 Environment.getProperty 取原始串+发送期 resolvePlaceholders（LeavePilotProcessDeployer 模式）；@Value 构造期与 @ConfigurationProperties 绑定期同罪。
- **运行时崩溃续任口径（轮 6 实证×2）**：ZCode 运行时崩溃会连带杀死后台任务/子代理/Docker Desktop（管道消失）——恢复顺序=清孤儿 JVM（powershell 按 classworlds 特征过滤，勿杀 VSCode 语言服务器家族）→拉起 Docker Desktop 轮询就绪→核对磁盘改动在位→续跑；只读代理崩溃零损失直接重派。
