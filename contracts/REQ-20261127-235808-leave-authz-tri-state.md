---
requirementId: "REQ-20261127-235808"
title: "B2b 后端正确性·越权与权限三态（P1-05/P1-08）：请假单越权收口（本人+授权角色）+ 权限判定 fail-closed 与完成链三态"
status: confirmed          # 2026-11-28 用户对话指示继续，按 leader 推荐口径确认（案B+两项默认）；未 confirmed 禁止进入 II-DEV
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-20261127-235808-leave-authz-tri-state"
migrations: {}             # 本批无 Flyway 迁移；若待确认项①改选案 A（新权限点种子），需另预分配 platform-system-service 迁移号并与 B1（V37）并行避让协调
apis:                      # 机器层路径清单（跨需求冲突扫描用）；两路径均既有，语义变更与同 Controller 占位防并行撞域
  - "GET /admin/v1/leave/requests/{id}"    # P1-05：语义变更（越权收口），路径与响应结构不变
  - "POST /admin/v1/leave/requests"        # 既有端点不变（LeaveRequestController 同文件改动面占位）
---

# B2b 后端正确性·越权与权限三态（P1-05/P1-08）

## 背景

整改批次 B2b（对应 `docs/code-review-remediation-plan-2026-11-27.md`「### B2b」节 + §9 风险注意事项；评审证据 `contracts/REVIEW-20261127-161433.md` P1-05/P1-08）。已复核钉死事实：

1. **P1-05**：`LeaveRequestUseCase.get(id)`（`business-leave/.../application/LeaveRequestUseCase.java:191-194`）仅 `findById`（租户维由 framework-tenant 插件自动过滤），无 owner 校验——同租户任一认证用户凭 id 可读他人请假单。租户隔离现状由框架承载，缺口仅在 owner 维。
2. 事件锚点兜底：`resolveByRuntimeQuietly`（`:223-235`）在 instanceId 查无时按数字形态 businessKey `Long.parseLong` 兜底 `findById`（`:230-232`）——businessKey 来自请求（`:80`），可写错单据。
3. **P1-08**：四域 `AuthorizationGateway`（auth/infra/scheduler/workflow-service `infrastructure/client/`）行为现状——auth 版 `hasPermission` **无 try/catch**（权限服务异常→500 非契约 403，`:37-41`）；其余三域 catch→false 已 fail-closed（infra 版 `:34-41` 同型）。
4. 下游查询异常→误写终态：`instanceVariablesFiled`（leave `:196-206`）catch RuntimeException→false，`onInstanceCompleted`（`:183-185`）据此直接写 REJECTED 终态——工作流详情查询瞬时故障即把未走备案链的单据错杀为 REJECTED。
5. 权限判定链：`AuthorizationClient`（platform-system-api，经 `LocalAuthorizationClient` 单体 @Primary 短路 / 分布式 Feign 双形态先例同 `WorkflowRuntimeClient`）→ `PermissionDecisionService`——roles 仅查询键、**无编码短路放行**（P0 修复后），权限码必须存在 sys_permission 种子行方可被授予；super_admin 能力来自种子全量绑定。
6. 「默认本人视图 + 权限点放宽」在库先例：`SystemPermissions.INFRA_AUDIT_LIST_ALL`（全租户审计视图，无此权限点仅本人视图）。leave 域当前**无任何权限点**（`LeaveRequestController` javadoc 自述「无细粒度权限注解」），`SystemPermissions` 无 `leave:*` 码。
7. `InvalidRequestException`（common-contract）经 framework-web 全局映射 **HTTP 400**——「查无」口径沿用该既有映射，无 wire 变更。
8. `WorkflowRuntimeClient.processInstanceDetail` 错误信封经 framework-feign 解码为异常（「未知 id → 404 信封，不经本类型解码」）——三态 UNKNOWN=异常路径，正常信封返回变量 Map 可判读 filed。
9. 门禁基线：后端集成测试已零 Docker 嵌入式化（提交 8e717ef）；Maven 不在 PATH，完整路径唯一口径 `.ai-memory/playbooks/flex-test-assembly.md`（`"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd"`）；**acme-cloud/ 下不存在 run.mjs（B1 leader 实测）**，整改方案 B0 节 `node run.mjs` 口径以本契约 Maven 路径口径替代。无统一 lint 脚本，禁表述为 lint 通过。

## 确认记录（契约的权威解释；只存结论条目，禁存对话原文）

- [2026-11-27] ① 决策③（owner 2026-11-27 拍板，整改方案 §5）：同租户请假单可见范围 = **本人 + 授权角色**——owner 或具备请假查看权限的管理员/审批人可读；本契约补细粒度口径（授权判定的权限码载体见待确认项①，未确认前对应 AC 冻结）。
- [2026-11-27] ② 事件 businessKey 解析以 **instanceId 为唯一锚点**，删 `Long.parseLong` 数字兜底写单路径（整改方案 B2b 节原文口径）。
- [2026-11-27] ③ P1-08 行为修复口径：auth 版 `hasPermission` 补 try/catch（异常→false→`requirePermission` 抛 PERMISSION_DENIED 403，非 500）；下游查询改三态 TRUE/FALSE/UNKNOWN，**UNKNOWN 不写 REJECTED 终态**（整改方案 B2b 节原文口径）。
- [2026-11-27] ④ **B7 边界**（主线程下达）：本批只做 fail-closed 与三态**语义**（行为修复）；「抽公共 AuthorizationGateway」的跨域结构上提（公共类/公共注解/拦截器收敛、四份副本合一）一律归 B7——四域网关仍为各自副本，本批不改其 API 形状。
- [2026-11-27] ⑤ 本批无 Flyway 迁移（frontmatter migrations 留空）；无前端改动；错误码全码统一属 B3b、事务/补偿属 B4、DLQ/重投基建属 B4——均不进本批。
- [2026-11-27] ⑥ 门禁口径（主线程下达）：后端本机按模块 `mvn -pl <模块> -am -B test`（Maven 完整路径见 flex-test-assembly.md；run.mjs 不存在，B1 leader 实测）；全量 mvn 交 CI，如实登记 SKIP≠全量通过；必须实际尝试本机模块级 mvn test，不得凭旧前提直接 SKIP。
- [2026-11-28] ⑦ 用户对话指示「继续」，按 leader 推荐口径确认全部待确认项：**①=案B**——授权角色放行复用 workflow 域既有种子码 `workflow:task:list`（审批面）/ `workflow:instance:manage`（管理面），**任一持有即放行**，零迁移；**②=接受** UNKNOWN 窗口单据滞留非终态取舍（消息重投/对账归 B4，本批不建）；**③=网关保持 boolean fail-closed**（不改 API 形状），三态 API 归 B7；**④=案B 组合按「任一持有」钉死**。AC-3 随之解冻，given 钉死为案B 两码任一持有。

## API

### 请假单详情（GET /admin/v1/leave/requests/{id}）——语义变更，路径/响应结构不变

- 鉴权面：B 端用户令牌（不变）；细粒度规则（决策③ + 确认记录⑦案B）：**owner 直读**（不经权限服务）→ 未命中 owner 时按授权权限码 `workflow:task:list` / `workflow:instance:manage` **任一持有即放行**（经 platform-system 实查，roles 不作信任锚）→ 全不命中返回与「单据不存在」同口径响应（HTTP 400 + `InvalidRequestException("请假单不存在：...")`，不泄露单据存在性）。
- 放行判定顺序：owner 判定先行（避免无谓跨域调用）；授权判定 UNKNOWN（权限服务异常/不可达）→ 不放行（fail-closed，查无）。
- 租户维维持 framework-tenant 自动过滤（跨租户 id 恒查无）。
- 返回：`Result<LeaveRequestView>`（不变）。新增失败语义：越权=查无（400，与不存在不可区分）；无新增错误码、无 500 口径。
- 不加 `@RequirePermission` 注解门（该范式为 all-or-nothing，与 owner-or-permission 语义不符）；细粒度口径以用例层实现 + Controller javadoc/openapi 注记承载。

### auth 域受保护端点（路径不变，故障口径变更）

- 既有 `requirePermission` 保护端点（LoginLogQuery/OnlineSession/ResetPassword/TenantSwitch 用例层）：权限服务异常时由 500 → **403 PERMISSION_DENIED**（`platform-system-07-0001`，既有 CrossDomainPermissionDenied 契约，HTTP 403）。正常授权/拒绝语义不变。

### 其余端点

- POST /admin/v1/leave/requests 语义不变（本人面）；infra/scheduler/workflow 受保护端点行为不变（已 fail-closed），仅回归锁定。

## 验收标准（AC 是规格核心；用例名=tester 交付后回填）

### P1-05 详情越权收口

- AC-1 given: 租户 T 用户 U1 已提交请假单 L（owner=U1） when: U1 携本人令牌 GET /admin/v1/leave/requests/{L} then: 200 返回 L 视图（owner 直读，不经权限服务） → REQ20261127235808LeaveDetailAuthzTest.ownerReadsOwnRequestDirectlyWithoutPermissionConsultation
- AC-2 given: 同租户用户 U2（非 owner、不持授权权限码） when: U2 凭 id GET 他人单据 L then: 与「单据不存在」同口径（HTTP 400 + 请假单不存在），不泄露存在性 → REQ20261127235808LeaveDetailAuthzTest.unauthorizedNonOwnerDeniedSameAsMissingRecord + missingUserContextTreatedAsNonOwnerAndDeniedAsMissing
- AC-3 given: 同租户用户 U2 非 owner 但持有 `workflow:task:list` 或 `workflow:instance:manage` 任一权限码（案B，确认记录⑦） when: U2 GET 单据 L then: 200 返回 L 视图（授权角色放行） → REQ20261127235808LeaveDetailAuthzTest.authorizedRoleReadsForeignRequestWhenTaskListPermissionHeld + authorizedRoleReadsForeignRequestWhenInstanceManagePermissionHeld
- AC-4 given: U2 非 owner 且授权判定 UNKNOWN（权限服务异常/不可达，故障注入） when: U2 GET 他人单据 then: 查无（UNKNOWN 不放行）；同场景 U1 读本人单据仍 200（owner 路径不依赖权限服务） → REQ20261127235808LeaveDetailAuthzTest.authorizationUnknownFailsClosedToNotFoundSemantics + leaveAuthorizationGatewayFailsClosedWhenPermissionServiceFails + ownerReadStillSucceedsWhenAuthorizationDownstreamFails（注入点=经真实网关在 AuthorizationClient 级注入，裁决①）
- AC-5 given: 租户 T1 用户凭 id 读取租户 T2 单据 when: GET 详情 then: 查无（租户过滤维持，框架层回归锁定） → REQ20261127235808LeaveDetailTenantIsolationIntegrationTest.should_returnLeaveView_when_ownerReadsOwnRequest + should_returnNotFoundSemantics_when_otherTenantUserReadsForeignRequest + should_beIndistinguishableBetweenCrossTenantReadAndMissingRecord
- AC-6 given: 事件（TASK_COMPLETED/TASK_REJECTED/INSTANCE_COMPLETED/ServiceTask 下发）携带 instanceId 可解析 when: 事件回写 then: 以 instanceId 为唯一锚点解析单据；数字形态 businessKey 不再触发 `findById(Long.parseLong)` 兜底；instanceId 查无 → 幂等跳过（warn 留痕），禁写单 → REQ20261127235808LeaveEventAnchorTriStateTest.taskCompletedResolvesByInstanceIdAnchorWithNumericBusinessKey + instanceCompletedResolvesByInstanceIdAnchorWithNumericBusinessKey + taskCompletedSkipsUnknownInstanceWithoutNumericBusinessKeyFallback + taskRejectedSkipsUnknownInstanceWithoutNumericBusinessKeyFallback + instanceCompletedSkipsUnknownInstanceWithoutNumericBusinessKeyFallback + fileByServiceTaskRejectsUnknownInstanceWithoutNumericBusinessKeyFallback

### P1-08 权限 fail-closed 与完成链三态

- AC-7 given: auth 域权限判定下游（platform-system authorization）异常/不可达（故障注入） when: 用户调用受 requirePermission 保护的 auth 端点（如登录日志查询） then: HTTP 403 + PERMISSION_DENIED 契约响应（非 500） → REQ20261127235808AuthorizationGatewayFailClosedTest（auth 域）.requirePermissionThrowsPermissionDenied403WhenAuthorizationClientFails + hasPermissionFailsClosedWhenAuthorizationClientFails（正常 true/false 路径另 3 例回归锁定）
- AC-8 given: 权限判定异常 when: infra/scheduler/workflow 域 requirePermission 判定（回归锁定） then: fail-closed 403（既有行为不回归） → REQ20261127235808AuthorizationGatewayFailClosedTest（infra/scheduler/workflow 域各一份）.hasPermissionFailsClosedWhenAuthorizationClientFails + requirePermissionThrowsPermissionDenied403WhenAuthorizationClientFails
- AC-9 given: INSTANCE_COMPLETED 消费时实例变量查询抛异常（故障注入） when: onInstanceCompleted 处置 then: **不写 REJECTED 终态**（单据保持原状态），warn 留痕，按幂等跳过口径返回；后续重投/正常查询可继续推进 → REQ20261127235808LeaveEventAnchorTriStateTest.instanceCompletedKeepsNonTerminalStateWhenInstanceDetailQueryFails + instanceCompletedConvergesWhenDetailQueryRecoversOnRedelivery（裁决②：「单据保持原状态」适用非 FILED 探询路径；FILED+查询故障→确定性 DONE=既有短路语义，由 AC-10 正常信封路径回归锁定）
- AC-10 given: 实例变量查询正常返回 filed=true / filed=false when: onInstanceCompleted 处置（回归锁定） then: 分别落定 DONE / REJECTED（既有正确行为不回归） → REQ20261127235808LeaveEventAnchorTriStateTest.instanceCompletedWritesDoneWhenFiledVariableTrue / instanceCompletedWritesRejectedWhenFiledVariableFalse / instanceCompletedWritesRejectedWhenVariablesEmpty + 既有回归锚 LeaveRequestUseCaseTest.instanceCompletedWritesDoneWhenFiledVariablePresent / instanceCompletedWritesRejectedWhenFilingChainNotReached
- AC-11 given: 四域 AuthorizationGateway（auth/infra/scheduler/workflow） when: 同权限码判定行为比对 then: fail-closed 语义一致（异常→不放行→403）；实现仍为四域各自副本（结构收敛=B7，见确认记录④） → REQ20261127235808AuthorizationGatewayFailClosedTest 四域同场景集逐字一致（auth/infra/scheduler/workflow 各 6 例，AC-11 同场景比对证据）

## 变更清单（差异契约：只写变更，不重述未变现状）

- **business-leave**：
  - `LeaveRequestUseCase`：`get(id)` 增 owner 判定 + 授权放行（owner 先行、授权 UNKNOWN fail-closed）；`resolveByRuntimeQuietly` 删数字 businessKey `Long.parseLong` 兜底（instanceId 唯一锚点）；`instanceVariablesFiled` 三态化（TRUE/FALSE/UNKNOWN）；`onInstanceCompleted` UNKNOWN 不写终态。
  - `LeaveRequestRepository`(+Impl)：增 owner 维度查询（tenant 维由框架承载）。
  - 新增 leave 域权限判定适配器（消费 `AuthorizationClient`，对齐四域 `AuthorizationGateway` 先例：显式 bean 名消歧 + fail-closed javadoc 口径；单体经 `LocalAuthorizationClient` @Primary 短路）；放行码 = `workflow:task:list` / `workflow:instance:manage` 任一持有（案B，确认记录⑦）。
  - `pom`：增 `platform-system-api` 显式依赖（现仅经 platform-workflow-api 传递可得）。
  - `LeaveRequestController`：javadoc/openapi 注记细粒度口径。
- **platform-auth-service**：`AuthorizationGateway.hasPermission` 补 try/catch（异常→false→`requirePermission` 抛既有 CrossDomainPermissionDenied 403）；javadoc 口径与三域对齐。
- **platform-infra/scheduler/workflow-service**：`AuthorizationGateway` 零行为改动（已 fail-closed）——仅回归锁定测试（AC-8/11），**不改 API 形状、不抽公共类**（B7 边界，确认记录④）。
- **测试**：business-leave 单测扩展（越权/锚点/三态）；auth 网关 403 故障注入用例；受影响的既有用例同步（如 `REQ20261122233425LeaveE2eIntegrationTest` 涉及跨用户读详情处）；事件消费侧故障注入用例。
- 数据：无表变更、无迁移。前端：无。
- 禁改（并行避让）：platform-system 全部（B1，含 V37 迁移目录）；platform-scheduler/platform-workflow 的客户端与 ServiceTask 注册面、framework-idempotent（B2a）；前端 mobile/web（B3a）。本批在 scheduler/workflow 域仅触碰各自 `infrastructure/client/AuthorizationGateway.java`（如需）及其测试，不涉 B2a 占用文件。

## 待确认项（已于 2026-11-28 全部经用户确认，结论见确认记录⑦；条目留档为追溯依据）

1. **授权角色权限码载体（待确认项①，影响 AC-3）——已确认：案B（复用 `workflow:task:list`/`workflow:instance:manage`，任一持有即放行，零迁移）**。原三案留档：
   - **案 A（新权限点，语义最正）**：`SystemPermissions` 增 `leave:request:view` + platform-system-service 种子迁移（sys_permission 行 + super_admin 等内置角色绑定）。与本批「无 Flyway 迁移」边界冲突，需 owner 解锁；解锁则 frontmatter migrations 增 platform-system-service 号（顺延 B1 的 V37 → V38，需与 B1 并行避让协调，取号时 git fetch 防撞）。
   - **案 B（复用既有种子码，本批默认草案）**：复用 workflow 域既有种子码（V27 种子 78~82）——审批面 `workflow:task:list` / 管理面 `workflow:instance:manage`，任一持有即放行（组合可调，见 4）。零迁移、super_admin 天然具备（全量绑定）、租户管理员可经既有角色授权面授予审批/管理角色；代价=语义为「借用工作流域权限码」，与「请假查看权限」命名不严格一致，且授权面粒度与 workflow 域共享。
   - **案 C（按实例审批人实查，备选记档）**：经 `processInstanceDetail` 活动任务 TaskView assignee 实查「本实例审批人」+ owner；不引权限点、语义最紧，但读路径增 workflow 查询依赖且「管理员」维仍需权限/角色承载，本轮不推荐。
2. **UNKNOWN 落点窗口语义——已确认：接受**（AC-9 下单据保持非终态，依赖消息重投/后续对账收敛；重投与对账基建属 B4，本批不建；fail-safe 取舍成立，对照现状：瞬时故障即错杀 REJECTED 终态且不可逆）。
3. **三态 API 形状边界——已确认：本批网关保持 `boolean hasPermission` fail-closed**（不改 API 形状），三态仅落在 leave 完成链下游查询（`instanceVariablesFiled`）；网关层三态 API 归 B7 结构收敛一并定（与整改方案「下游查询改三态」原文的对应关系已按评审证据 `:196-206`/`:184` = leave 完成链查询口径落地）。
4. **案 B 权限码组合——已确认：「任一持有即放行」**（`workflow:task:list` OR `workflow:instance:manage`）。

## 流程状态（TL 每次行动后更新；这是唯一现场，跨会话据此恢复）

- phase: III-REVIEW       # I-REQ | I-DONE | II-DEV | III-GATE | III-TEST | III-REVIEW | DONE
- round: 0
- 取号锁：worktree 已创建（2026-11-28）——分支 `feat/req-20261127-235808-leave-authz-tri-state`，基点 f3bf0c1（含本契约 confirmed 版），路径 `C:/MyWorkSpace/ai-coding-worker/.worktrees/feat-req-20261127-235808-leave-authz-tri-state`；本批无 Flyway 迁移（migrations: {} 维持）。
- 门禁台账（2026-11-28，阶段三全过）：
  - 三向一致: PASS（FE `packages/core/src/api/leave.ts:48` `GET /admin/v1/leave/requests/{id}` = Controller `@GetMapping("/requests/{id}")` 逐字一致，POST 同（leave.ts:44）；网关无 leave 路由=既有基线缺口（P1-09，B6 批/决策②单体试点），非本批引入、本批零路径变更；案B 放行链路核对成立——leave 适配器→`AuthorizationClient`→`LocalAuthorizationClient`(单体 @Primary)→`PermissionDecisionService`，roles 仅查询键、无编码短路）
  - AC 覆盖率: PASS（AC-1~11 每条 ≥1 用例，映射已回填 §验收标准；46 例 = business-leave 19 / auth 6 / infra 6 / scheduler 6 / workflow 6 / bootstrap 3）
  - 契约机器层: PASS（`node .ai/skills/team-leader/scripts/check-contract.mjs` → `[GATE] contract-valid PASS`）
  - 本地测试: PASS（采信 worker 自验合并证据：backend-dev 模块级门禁绿——business-leave + platform-auth-service，reactor 汇总 14/202；tester 六模块定向自验绿 46 例 0 失败 0 跳过；全量 mvn 交 CI；主线程合并前复核重跑兜底）
  - 跨需求冲突: PASS（复扫 contracts/：B1=REQ-20261127-234335［platform-system V37］、B2a=REQ-20261127-235505［internal scheduler 域］、B3a=REQ-20261127-234158［auth tokens/scheduler jobs/system menus］与本批 `GET|POST /admin/v1/leave/requests` + `migrations: {}` 零交叠）
- 委派历史：
  - backend-dev: 交付（2026-11-28；main 侧 LeaveAuthorizationGateway 新增 / UseCase 5 参与行为序 / Repository+Impl findByIdAndUserId / pom 显式依赖 / Controller javadoc / auth 版 try/catch / 既有用例适配（LeaveRequestUseCaseTest 构造器同步等）；自验两模块门禁绿 14/202）
  - tester: 交付（2026-11-28；7 测试文件 46 例，六模块定向自验绿 0 失败 0 跳过；四域网关用例同场景集逐字一致=AC-11 比对证据；上报 3 项裁定 + 1 项知情备案，均裁决见下）
- 裁决记录（2026-11-28，主线程转 owner 口径）：
  - ① AC-4 故障注入点 = 经真实 `LeaveAuthorizationGateway` 在 `AuthorizationClient` 级注入（fail-closed catch 落网关内部，与四域先例一致；UseCase 层不加防御 catch——同语义重复且违背单件职责）；委派单钉死事实 2 勘误为「经真实网关、client 级注入」。
  - ② AC-9「单据保持原状态」适用**非 FILED 探询路径**；FILED+查询故障 → 确定性 DONE（`status==FILED` 短路既有语义，委派单钉死事实 4 原口径），事实 5「保持原值」仅限非 FILED 路径；评审 P1-08 要修的错杀场景（非 FILED 查询异常→误写 REJECTED）已绿（APPROVING 保持 + WARN 留痕）。
  - ③ wire 消息口径无改动：单测层钉 `InvalidRequestException` 原文、wire 层既有 WebError 统一映射（400+请求参数不合法）属本契约「无 wire 变更」范畴（背景事实 7）；双失败路径信封归一后全等 = 存在性零泄露，AC-2/AC-5 判据成立。
  - ④ tester 只读 Flyway 种子/DDL（数据 schema 事实、非实现逻辑）知情备案，不作违规。
- 升级记录：
  - （无打回轮；round 维持 0）
- 并行避让注记：B1=REQ-20261127-234335（platform-system RLS + V37）；B2a=REQ-20261127-235505（scheduler DELETE 端点 + framework-idempotent）；B3a=REQ-20261127-234158（前端 DTO 面 + auth/scheduler/system DTO）。本批 apis 与三份并行契约零交叠（冲突预检 2026-11-27 通过）。
