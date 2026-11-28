---
requirementId: "REQ-20261128-092210"
title: "B4-事务 业务事务与消息可靠性·leave 域（P1-03/P1-04）：apply 事务化+afterCommit 启流程补偿重试 + ServiceTask 备案去 sleep 轮询"
status: confirmed          # 2026-11-28 用户对话确认推荐口径（待确认①~⑤全按推荐定案）；confirmed 后禁止改范围，歧义走裁决记录
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-20261128-092210-leave-tx-reliability"
migrations:
  business-leave: "V2"     # TL 预分配（2026-11-28 git fetch 后实测：leave 厂商迁移系列仅 V1——按厂商独立编号，下一空号=V2；禁工人自行取号）
apis:                      # 机器层路径清单（跨需求冲突扫描用）；两路径均既有，语义变更与同 Controller 占位防并行撞域（B3b REQ-20261128-051442 已以同两路径占位）
  - "POST /admin/v1/leave/requests"        # P1-03：语义变更（启动流程后置 afterCommit；响应 view.instanceId=null），路径与校验不变
  - "GET /admin/v1/leave/requests/{id}"    # 既有端点不变（instanceId 异步回写后详情可读），LeaveRequestController javadoc 改动占位
---

# B4-事务 业务事务与消息可靠性·leave 域（P1-03/P1-04）

## 背景

整改批次 B4-事务（对应 `docs/code-review-remediation-plan-2026-11-27.md`「### B4 业务事务与消息可靠性」leave 域两项 + §9 风险；评审证据 `contracts/REVIEW-20261127-161433.md` P1-03/P1-04）。**P1-05/P1-08 已由 B2b（REQ-20261127-235808，56032b5）合流**，以下钉死事实全部基于合并后主线 tip e6c0c59 复核（2026-11-28）：

1. **P1-03 证据复核成立**：`LeaveRequestUseCase.apply()`（`business-leave/.../application/LeaveRequestUseCase.java:68-94`）无 `@Transactional`——`save(APPLYING)`（:83）→ `workflowRuntimeClient.startProcessInstance`（:85-88，分布式 Feign / 单体 Local 短路）→ `save(instanceId)`（:89-90）。启动失败时异常直接外抛（响应 5xx），单据永久停留 APPLYING 且 instance_id 空，无补偿/重试/告警面。
2. **P1-04 证据复核成立**：`fileByServiceTask`（:103-129）遇 APPLYING 走 `awaitApproving`（:131-146）——`Thread.sleep(200ms)`×最多 25 轮占 Servlet 线程 5s；超时仍 APPLYING 时**照写 FILED**（:120-124，warn 留痕），覆盖状态机守卫。两处常量 `FILE_AWAIT_TIMEOUT_MS=5_000`/`FILE_AWAIT_POLL_MS=200`（:44-47）随修复删除。
3. **单体形态事件面缺席（P1-04 选型决定性事实）**：bootstrap `acme.stream.enabled=false`（`acme-bootstrap/src/main/resources/application.yml:92-94`）→ `WorkflowConfiguration` 装配 `LoggingWorkflowEventPublisher` 兜底——workflow 事件（含 TASK_COMPLETED）**仅进日志、永不消费**；`onTaskCompleted` 是 APPROVING 唯一推进入口（`LeaveWorkflowEventConsumer` stream 消费），故单体生产态 APPROVING 事件腿不存在，「超时照写 FILED」在单体是**常态路径**（每单备案必先 5s 轮询+warn 照写）。E2E 测试域显式开启 stream+嵌入式 Kafka（`REQ20261122233425LeaveE2eIntegrationTest` A-7 注记）才走事件腿。
4. **引擎侧委托重试语义既有**：`ExecutionEngine.dispatchServiceTask`（platform-workflow-service `:277-315`）下发失败（含 SDK 接收端点 5xx）→ 按节点属性 `acme:retryMaxAttempts`/`acme:retryBackoffMs` 重试，**耗尽→挂起实例（SERVICE_TASK_FAILED）**；重试配置缺省 0（WorkflowProperties），**试点 BPMN 已显式 `acme:retryMaxAttempts="3" acme:retryBackoffMs="1000"`**（`LeavePilotProcessDeployer.pilotBpmnXml` hr-file 节点）。
5. **SDK 双模式**：`WorkflowServiceTaskReceiveController` 支持 `acme.workflow.service-task.receive-async`（缺省同步=handler+回调内联下发线程，失败 5xx 反噬引擎→重试/挂起；异步=受理即 2xx，handler 异常仅 warn 不反噬、单线程 daemon executor）——异步模式无重试/挂起面，不适合 P1-04（见待确认项①）。
6. **前端兼容零改动**：`packages/core/src/api/leave.ts` 已声明 `instanceId: string | null`（注释「instanceId 可空=未关联流程实例」）——apply 响应 instanceId=null 无 wire 破坏。
7. **乐观锁与锚点**：`AuditBase` 携 `@Column(version = true)` 乐观锁（0 行更新=STALE_WRITE）；`resolveByRuntimeQuietly` 已删 businessKey 数字兜底（B2b），instanceId 唯一锚点；leave_request.instance_id 仅普通索引（`V1__init_leave_request.sql:28`），无唯一约束。
8. **迁移编号实测**：leave 厂商系列独立编号（`MonolithFlywayConfig` 每域独立 Flyway 实例+独立历史表，`business-leave/.../db/migration/leave/` 仅 V1）；git fetch 实测后预分配 **business-leave=V2**（全局 system 系列 V37 与本批无关）。Flyway 只前向、新迁移幂等口径照 V30/V31 先例（IF NOT EXISTS）。
9. **测试底座**：后端集成测试已零 Docker 嵌入式化（8e717ef，common-test `EmbeddedPostgresSupport` 等）；`@Transactional` 用例先例=platform-system `TenantUseCase`；后台任务先例=platform-infra `AuditRetentionCleanupJob`（@Scheduled+JobHandler+ShedLock——ShedLock 单体失效已知 P2-06，本批不依赖之，正确性由乐观锁认领承担）；@EnableScheduling 先例=`InfraSchedulingConfig`（business-leave 模块内需自建同款）。
10. **并行避让**：B3b（REQ-20261128-051442 错误码）持有前端+MSW 面，其后端唯一触碰=api 模块 5 个 REQ 前缀快照测试——本批不触前端、不触 api 模块；B4-消息（P1-11/P2-12）持有 workflow-service/stream 域——本批禁改 platform-workflow 主代码与 framework-stream；禁改 framework-feign/framework-idempotent（B2a 面）与 platform-system RLS 面（V37 已合入）。

## 确认记录（契约的权威解释；只存结论条目，禁存对话原文）

- [2026-11-28] ① P1-04 定案=**下发权威推进**：APPLYING 收 ServiceTask 下发即两步前向 APPROVING→FILED（AC-12 口径随之）；引擎重试/挂起仅承接瞬时故障（AC-15）。整改方案 §4 P1-04 行已由主线程同步演进标注（理由与「去轮询占线程」「超时不写终态」两本意满足）。
- [2026-11-28] ② P1-03 补偿形态=**leave_start_task 补偿表 + leave_request(instance_id) 部分唯一索引**（V2 迁移；business-leave 厂商独立编号系列实测下一空号，frontmatter 已预分配）。
- [2026-11-28] ③ 重试参数缺省定案：扫描 10s / 指数退避 1s→2s→4s→8s→16s / 上限 5 次尝试 / RUNNING 租约 2min / 零尝试 grace 10s；键面 `acme.leave.start-retry-*` 可配。
- [2026-11-28] ④ EXHAUSTED 仅结构化 warn 留痕；告警通道与 DLQ 面归 P1-11（B4-消息 批）；管理端人工重驱不进本批。
- [2026-11-28] ⑤ L1 压测移交（备案下发 P95 无 5s Servlet 线程占用、无轮询 DB 放大），本批不执行，契约注明即闭环。

## API

### 提交申请（POST /admin/v1/leave/requests）——P1-03 语义变更
- 路径、`@Valid` 校验、鉴权（B 端用户令牌面）全部不变。
- 行为变更：`apply()` 加 `@Transactional`——同事务原子落库 leave_request(APPLYING) 行与补偿任务行（leave_start_task，PENDING，attempts=0）；**流程启动移出事务**，在 afterCommit（`TransactionSynchronization`）执行：认领任务行（乐观锁 CAS）→ `startProcessInstance` → 成功回写 `instance_id` + 任务 DONE；失败**全捕获不反噬**（响应恒 2xx），任务行 PENDING+指数退避+结构化 warn。
- 响应：`Result<LeaveRequestView>`（`status=APPLYING`；**`instanceId=null`**——启动已后置，成功回写后经 `GET /requests/{id}` 可读；前端已兼容可空）。
- 无活跃事务同步时（直调/单测态）降级为内联立即启动（framework-core BR-017 防丢失先例语义），不静默丢启动。
- 失败码：既有校验失败码不变；**流程启动失败不再产生 5xx**（转入补偿重试面）。

### 详情（GET /admin/v1/leave/requests/{id}）——不变
- 既有 owner+授权角色语义（B2b 案B）不变；instanceId 字段为异步回写结果，启动成功前为 null。

## 验收标准（规格核心；AC 是规格+追溯锚点+覆盖率核对单元）

### P1-03 apply 事务化与补偿重试
- AC-1 given 已认证用户提交合法申请 when `apply()` 执行 then leave_request(APPLYING) 与 leave_start_task(PENDING, attempts=0) **同事务**原子落库；响应 2xx、`status=APPLYING`、`instanceId=null` → {用例名，tester 交付后回填}
- AC-2 given 事务提交、流程启动成功 when afterCommit 执行 then leave_request.instance_id 回写为启动实例 id、补偿任务行置 DONE → {用例名}
- AC-3 given 流程启动抛异常（连接失败/BizException）when afterCommit 执行 then 响应仍 2xx（异常全捕获不反噬）；单据保持 APPLYING 非死态；任务行 PENDING、attempts≥1、next_retry_at 指数退避、last_error 留痕 → {用例名}
- AC-4 given 任务行 PENDING 且 next_retry_at 到期 when 重试任务扫描认领并启动成功 then instance_id 回写+任务 DONE；并发认领竞态时乐观锁败者跳过（恰一执行者） → {用例名}
- AC-5 given 启动持续失败 when 重试轮次达上限（缺省 5 次尝试） then 任务置 EXHAUSTED+结构化 warn 留痕；leave 行保持 APPLYING、不写 APPROVING 及任何终态 → {用例名}
- AC-6 given JVM 在提交后、内联启动前崩溃（任务行遗留 PENDING attempts=0）when 扫描捞起超过 grace 期（缺省 10s）的零尝试行 then 正常补投启动（防丢失） → {用例名}
- AC-7 given RUNNING 任务行执行器崩溃超租约（缺省 2min 无更新） when 扫描回收 then 回置 PENDING 重投（自愈） → {用例名}
- AC-8 given 分布式多实例/双节点 when 并发认领与启动 then 乐观锁认领保证恰一执行者调 startProcessInstance；`leave_request(instance_id)` 部分唯一索引作双启动最后兜底 → {用例名}
- AC-9 given 无活跃事务同步（直调/单测态） when `apply()` 执行 then 启动立即内联执行（BR-017 防丢失语义），行为与事务路径收敛一致 → {用例名}
- AC-10 given 启动失败后补偿重试成功（集成链） when 以嵌入式 PG 集成测试实跑 then「失败入补偿→重试回写 instanceId→任务 DONE」全链断言通过（启流程失败集成用例，零 Docker 底座实跑非 SKIP） → {用例名}

### P1-04 ServiceTask 备案去轮询
- AC-11 given 请假单 APPROVING when HR 备案下发到达 then FILED 落库（既有正常路径不变） → {用例名}
- AC-12 given 请假单 APPLYING（TASK_COMPLETED 消费滞后/单体无事件面）when ServiceTask 下发到达 then 无任何 `Thread.sleep`/轮询等待；**下发即审批办结权威证据**（token 必经审批 UserTask），两步前向推进 APPROVING→FILED（各一次乐观锁落库，无状态跳写） → {用例名}
- AC-13 given 请假单已 FILED when 重复下发 then 幂等直返 `filed=true`（不变） → {用例名}
- AC-14 given 请假单终态 DONE/REJECTED when 下发到达 then 不写状态、返回 `filed=false+reason`（不变） → {用例名}
- AC-15 given handler 瞬时异常（DB 故障/乐观锁冲突）when SDK 接收端点 5xx 反噬 then 引擎按试点节点属性重试（3 次×1s 退避），耗尽挂起 SERVICE_TASK_FAILED——FILED 不被写入，终态判定仍归事件面 → {用例名}
- AC-16 given TASK_COMPLETED 事件消费者与备案 handler 并发 when 同行乐观锁竞态 then 败者重试/跳过收敛，状态机单向推进无回退（消费侧见非 APPLYING 即跳过） → {用例名}
- AC-17 given 既有 E2E 全链（嵌入式 Kafka stream 使能） when 申请→审批→备案→实例终结 then 状态链 APPLYING→APPROVING→FILED→DONE 保持，适配后仍绿 → {用例名}

## 设计定案

### P1-04 选型：下发权威推进（事件驱动变体），异步化否决
- **定案**：删除 `awaitApproving` 与两常量，无等待无超时。handler 收下发时若见 APPLYING，以「token 已到达 hr-file ServiceTask」为审批办结的权威证据（试点 BPMN 单链 start→approval(userTask)→hr-file，无旁路），执行 APPROVING→FILED 两步前向落库（与 `onTaskCompleted` 同义同守卫；APPROVING 语义=审批办结后备案前，与 UseCase javadoc 一致；LeaveStatus 枚举上陈旧的「流程实例启动成功」注释随本批对齐）。
- **与方案字面口径的偏差说明（待确认项①）**：方案写「超时不写 FILED 终态，走重试/失败态」。本设计下无超时路径，APPLYING 写 FILED 不再是「超时猜测」而是「下发权威证据」的确定性推进；**若按字面口径（APPLYING→抛可重试异常→引擎重试），单体形态（stream 关闭、TASK_COMPLETED 永不消费）下每单备案必然 3×1s 重试耗尽→挂起 SERVICE_TASK_FAILED，试点流程全断**——故推荐下发权威推进；引擎重试/挂起仅承接真实瞬时故障（AC-15），与「走重试/失败态」意图吻合。备选 A'（字面口径）与备选 B（`receive-async` 异步化：失败不反噬无重试面+单线程共享 executor+不解决 APPROVING 推进）均记录否决理由于背景 3/5。
- **竞态收敛**：事件消费者（stream 使能形态）与 handler 并发时乐观锁裁决，败者 STALE_WRITE 自愈（消费侧重投见 APPROVING 跳过；handler 侧 5xx 引擎重试）。
- **L1 压测移交**：备案下发 P95 无 5s Servlet 线程占用、无轮询 DB 放大——交 CI/L1 压测（不进本批，契约注明）。

### P1-03 补偿表设计（待确认项②）
- **V2 迁移（business-leave 厂商系列，幂等 IF NOT EXISTS）**：
  - `CREATE TABLE IF NOT EXISTS business_leave.leave_start_task`：id（雪花）、tenant_id、leave_id（唯一，一单一任务）、status（PENDING/RUNNING/DONE/EXHAUSTED）、attempts、next_retry_at、last_error、审计列对齐 AuditBase（version 乐观锁/deleted/created_*）；实体 extends AuditBase，`@Column(tenantId=true)` 对齐 leave_request。
  - `CREATE UNIQUE INDEX IF NOT EXISTS uk_leave_start_task_leave ON leave_start_task (leave_id)`；
  - `CREATE INDEX IF NOT EXISTS idx_leave_start_task_scan ON leave_start_task (status, next_retry_at)`；
  - `CREATE UNIQUE INDEX IF NOT EXISTS uk_leave_request_instance_uid ON leave_request (instance_id) WHERE instance_id IS NOT NULL`（双启动兜底，防任务认领竞态之外的最后防线；存量单实例 pilot 无重复数据风险）。
- **选补偿表而非 leave_request 加列的理由**：重试管道与单据状态机隔离——重试认领/回写不碰 leave_request 行的乐观锁版本，避免与事件消费者状态推进相互 STALE_WRITE 噪声；任务行自带 attempts/last_error/租约语义，扫描有专属索引。
- **状态机**：PENDING(attempts=0 初始)→（内联认领 CAS→RUNNING）→成功 DONE / 失败 PENDING(attempts≥1, 指数退避 1s→2s→4s→8s→16s)；RUNNING 超租约 2min 回 PENDING；attempts≥5 → EXHAUSTED。参数可配 `acme.leave.start-retry-*` 键。
- **重试任务**：business-leave 模块内 `@EnableScheduling` 配置 + `@Scheduled(fixedDelayString="${acme.leave.start-retry-scan-ms:10000}")`；扫描不绑 TenantContext（跨租户；flex tenantId=true 仅上下文在场时过滤，audit retention 同款），认领经乐观锁（正确性机制，不依赖 ShedLock——P2-06 单体失效已知）；启动前按任务行 tenant_id 显式回放 TenantContext、finally 清理（LeaveWorkflowEventConsumer 同款）；多实例安全（乐观锁认领恰一执行者）。
- **afterCommit 异常边界**：afterCommit 体内全捕获 RuntimeException，失败只写补偿+warn，异常不外抛（外抛会使 commit 后的响应变 5xx——违背「不落死态」意图）。

## 变更清单（差异契约：只写变更，不重述未变现状）

- 数据：business-leave V2（上节三表级变更+索引；幂等口径照 V30/V31 先例；迁移号 frontmatter 预分配，工人禁自行取号）。
- 后端 main（仅 business-leave 模块）：
  - `LeaveRequestUseCase`：apply 事务化+afterCommit 启动+补偿登记；`fileByServiceTask` 重写（删 awaitApproving/两常量，下发权威推进）；类注状态链 javadoc 对齐。
  - 新增：`LeaveStartTask` 领域实体、仓储（认领/扫描查询）、`LeaveStartRetryJob`、模块内 `@EnableScheduling` 配置类。
  - `LeaveRequestController` / `LeaveStatus`：javadoc 对齐（APPROVING 语义、apply 启动后置说明）。
- 测试：`LeaveRequestUseCaseTest` 适配（apply 断言改造：afterCommit 注册+内联回退；fileByServiceTask 两个轮询/超时用例重写为权威推进用例）；新增 REQ 前缀测试类（补偿集成 AC-10、重试任务 AC-4~8、竞态 AC-16），**新增文件名带 REQ 前缀**（`REQ20261128092210*.java`）防并行撞车；`REQ20261122233425LeaveE2eIntegrationTest` 适配（如 afterCommit 后 instanceId 时序断言需微调）。
- 前端：零改动（core leave.ts `instanceId: string | null` 已兼容）。
- 禁改面：前端全部（B3b 持有）、platform-workflow 主代码与 framework-stream（B4-消息 持有）、framework-feign/framework-idempotent（B2a 面）、platform-system（V37 面）、framework-workflow SDK（本批只消费不改造）。

## 门禁口径（B0 基线）

- 后端本机按模块：`cd workspace/apps/backend/acme-cloud && "C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd" -pl :business-leave -am test`（裸名不可解析时以 `:artifactId` 形态）；判定 `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`（零 Docker 嵌入式底座必须实跑，集成用例不得 SKIP 豁免）。全量 mvn 交 CI，如实登记 SKIP≠全量通过；无统一 lint 脚本，禁止表述为 lint 通过。
- 契约机器层：`node .ai/skills/team-leader/scripts/check-contract.mjs contracts/REQ-20261128-092210-leave-tx-reliability.md`。
- 跨需求冲突：B3b 同两 leave 路径已互相占位声明（语义不冲突）；migrations 无撞号（leave V2 为厂商系列实测空号）。

## 待确认项（交用户逐项裁决；均有推荐口径）

- ① **P1-04 推进语义**：推荐「下发权威推进」——APPLYING 收下发即两步前向 APPROVING→FILED（单体 stream 关闭使字面「不写+重试」口径必致每单挂起，见设计定案偏差说明）。备选 A'（字面口径：APPLYING 抛异常→引擎重试→耗尽挂起）否决理由如上。**若用户坚持字面口径，单体试点需同时接受备案挂起面。**
- ② **补偿表+唯一索引（V2 迁移）**：推荐照建（补偿表隔离重试管道+instance_id 部分唯一索引兜底）；若倾向零迁移方案（leave_request 加列），代价=重试管道与状态机同表竞争乐观锁+扫描噪声，不推荐。
- ③ **重试参数缺省值**：扫描 10s / 退避 1s→2s→4s→8s→16s / 上限 5 次尝试 / RUNNING 租约 2min / 零尝试 grace 10s，全部 `acme.leave.start-retry-*` 可配——缺省值是否接受。
- ④ **告警与人工重驱边界**：EXHAUSTED 仅结构化 warn 留痕，告警通道与 DLQ 面归 P1-11（B4-消息 批）、管理端人工重驱不在本批——确认接受。
- ⑤ **L1 压测移交**：P1-04 性能断言（无 5s 线程占用/轮询 DB 放大消除）交 CI/L1 移交项，本批不执行——确认口径。

## 流程状态（TL 每次行动后更新；这是唯一现场，跨会话据此恢复）

- phase: DONE             # 2026-11-28 主线程收口（用户指令：并发受限，后续工作全主线程完成）：tester 18/18 集成绿但披露①生产级缺陷（UNBOUND_TENANT 哨兵致 @Scheduled 补偿死面）→round-1 主线程修复（IgnoreTenant 门面+逐任务绑定行租户，回归证明=无上下文直驱 12 例）→合并 db93c80（含整批：V2 迁移/事务化/下发权威推进）。tester 披露 2/3/4/5 已录（attempts=失败计数、businessKey=leaveId 数字串、warn 日志面未钉、ShedLock 双 provider 噪声=N-01 同域）
- round: 0                # 连续修复轮次，满 3 触发升级
- 门禁台账：
  - （未进入 III-GATE）
- 委派历史：
  - II-DEV 待发：backend-dev / tester 两单同轮并行（委派单全文已交主线程，2026-11-28）
- 升级记录：
  - （无）
