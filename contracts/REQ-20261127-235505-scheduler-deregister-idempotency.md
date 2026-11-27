---
requirementId: "REQ-20261127-235505"
title: "B2a 后端正确性·注销与幂等（P1-01/P1-02/P2-17）：scheduler DELETE 注销端点补齐 + 幂等门 complete 失败不释放"
status: confirmed          # 2026-11-28 用户对话确认（见确认记录⑥）；confirmed 后方进 II-DEV
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-20261127-235505-scheduler-deregister-idempotency"
migrations: {}             # 本批无 Flyway 迁移
apis:                      # 机器层路径清单（跨需求冲突扫描用）
  - "POST /internal/v1/scheduler/jobs"                   # 既有端点不变（随域声明防并行撞域；internal 域不经网关）
  - "DELETE /internal/v1/scheduler/jobs/{jobKey}"        # 新增（P1-01；Feign 契约 SchedulerJobClient 早已声明）
---

# B2a 后端正确性·注销与幂等（P1-01/P1-02/P2-17）

## 背景

整改批次 B2a（对应 `docs/code-review-remediation-plan-2026-11-27.md`「### B2a」节 + §9；评审证据 `contracts/REVIEW-20261127-161433.md` P1-01/P1-02/P2-17）。已复核钉死事实：

1. P1-01 断层：`SchedulerJobClient.java:35`（platform-scheduler-api）Feign 已声明 `DELETE /internal/v1/scheduler/jobs/{jobKey}`，服务端 `InternalJobRegistrationController` 仅有 `@PostMapping`；两处 Javadoc 自认「REQ-20261121-071002 未交付该端点」。workflow 侧 `TimerScheduler` 在 AFTER_COMMIT 注销走 `SchedulerRegistrationClient.unregister`（失败仅告警不阻断本地清理）→ 404 被吞为幂等收敛，孤儿一次性任务到点触发。
2. isNotFound 字符串匹配（`SchedulerRegistrationClient.java:75-78`）：`message.contains("404") || message.contains("Not Found")`——路由缺失/无关文案含 404 均被吞成「已注销」。同文件 `isDuplicateKey`（`:70-73`）为同型反模式（contains "platform-scheduler-01-0001"/"409"），plan 未列入本批（见待确认③）。
3. Feign 错误解码丢 HTTP 状态（framework-feign `FeignErrorDecoder.decode`）：错误响应统一转 `BizException`（标准信封→`RemoteErrorCode(code,message)`，非标准→`FeignError.CALL_FAILED`），HTTP 状态码不随异常携带——「按 HTTP 状态码判定 404」在现状下无可判定载体，需 framework-feign 最小扩展（见待确认①）。`RemoteErrorCode` 仅 framework-feign 内部引用，无外溢。
4. DELETE 端点按本批口径交付后（不存在 jobKey → 成功语义），常态下 unregister 不再遇 404；404 分支仅保留为旧版 scheduler/路由缺失的降级容错（Feign 契约 javadoc 既定协议：「workflow 侧注销 404 收敛为幂等成功，不阻断本地清理」）。
5. scheduler 侧注销复用既有软删面：`JobUseCase.delete(Long id)`（kernel.removeWheel + repository.delete 软删，执行历史保留）；`definitionRepository.findByJobKey(jobKey)` 已存在。jobKey = `wf-timer:{timerJobId}`。
6. P1-02 现状（`HttpIdempotentGate.java:42-53`）：CLAIMED 分支将 `complete()` 抛出的 `BizException(STORE_UNAVAILABLE)`（RuntimeException）与 action 失败同捕并 `release(key)` → 业务已成功但认领被释放，同 token 重试重新认领重执行 → 重复执行，与 javadoc fail-closed 矛盾。`RedisIdempotentRecordStore` 的认领/完成/释放为单键 Lua 原子（三态 P/D + TTL）；markDone 失败时记录维持 P + claimProcessingTtl。现有单测 `HttpIdempotentGateTest`（9 例）仅覆盖 tryBegin 失败（storeFailureIsFailClosed），无 markDone 失败用例。
7. P2-17 现状（`HttpIdempotentGate.replay:83-90`）：DUPLICATE 分支 `findResult=null` 时静默返回 null → 调用方拿到「成功但空结果」。合法 null 返回值经 `JsonResultCodec.encode(null)` 落库为 JSON 字面量 `"null"`（非空串）回放为 null，不落入该分支——修复不得破坏合法 null 回放。
8. 测试底座（8e717ef 零 Docker 嵌入式）：platform-scheduler-service 集成测试用 `EmbeddedPostgresSupport`（先例 `REQ20261121200724SchedulerV2IntegrationTest` 覆盖 internal 注册端点：真实 MVC + internal token）；本机全量 mvn 实测约 7 分钟可行。
9. 门禁口径（B0 更正为准）：Maven 不在 PATH，用 `"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd"`；模块级 `-pl <模块> -am -B test`（cwd=`workspace/apps/backend/acme-cloud/`）；原文案 `node run.mjs --mvn` 作废（acme-cloud 下无 run.mjs）；全量 mvn 交 CI；无统一 lint 脚本，禁表述为 lint 通过。

## 确认记录（契约的权威解释；只存结论条目，禁存对话原文）

- [2026-11-27] ① 批次范围 = B2a 三项（P1-01/P1-02/P2-17），修复口径以整改方案「### B2a」节为准（契约不得偏离）；验证 = 三模块模块级 mvn test + 新增失败分支用例。
- [2026-11-27] ② P1-01 注销幂等语义：不存在 jobKey → 成功语义（HTTP 200 + Result success），不新增任何错误码；本批无 Flyway 迁移。
- [2026-11-27] ③ P1-02 语义：complete() 失败不 release、保留 P 至 claimProcessingTtl；action 失败仍 release 允许重试（10.3.3 语义不变）。
- [2026-11-27] ④ 测试落点：新增测试一律用新增独立测试类（不改动既有 REQ 命名测试文件），避免与并行批 B3a 在 platform-scheduler-service 新增的 wire 测试文件相撞。
- [2026-11-27] ⑤ 待确认项①~④未获用户裁决前，对应改动面冻结不下发阶段二。
- [2026-11-28] ⑥ 用户对话指示「继续」，按 leader 推荐口径逐项确认四项待确认项：①=framework-feign 入批、方案 A（新增公共 BizException 子类携带 httpStatus，`RemoteErrorCode` 与既有 catch 面不动）；②=DUPLICATE+findResult=null 抛 STORE_UNAVAILABLE（SYS-11-0003/503，fail-closed）；③=isDuplicateKey 随批改结构化 errorCode 判定（锚点常量 `SchedulerErrorCodes.SCHEDULER_JOB_KEY_DUPLICATE="platform-scheduler-01-0001"`）；④=DELETE 不限制仅 ONCE 类型。
- [2026-11-28] ⑦ AC-1 的「时间轮条目移除」无独立 HTTP 观测面：集成测试以「注销后再注册同 jobKey 成功」作软删查无的黑盒代理断言；时间轮移除由 backend-dev 以复用 `JobUseCase.delete` 同款代码路径落实并在交付说明中声明。

## API

### DELETE /internal/v1/scheduler/jobs/{jobKey}（新增，P1-01）
- 内部协议端点（internal token 保护域，不经网关对外；与既有 POST 同控制器）。
- 幂等：jobKey 不存在 → HTTP 200 + `Result<Void>` success=true（不抛 NOT_FOUND、无新增错误码）。
- 存在 → 软删（复用 `JobUseCase.delete` 语义：kernel.removeWheel + repository.delete，执行历史保留）→ HTTP 200 success。
- jobKey 不限制任务类型（默认口径，待确认④）；返回：`Result<Void>`（code="0"，data=null）。

### workflow 客户端语义（SchedulerRegistrationClient.unregister，无 HTTP 面）
- isNotFound 改按 HTTP 状态码判定（异常携带状态的载体=确认⑥方案 A 新增公共异常类型）：仅 404 收敛为幂等成功；其余失败照常上抛（TimerScheduler 告警不阻断本地清理，语义不变）。
- isDuplicateKey 随批改结构化 errorCode 判定（确认⑥③）：`failure.errorCode().code()` 与锚点常量 `SchedulerErrorCodes.SCHEDULER_JOB_KEY_DUPLICATE`（platform-scheduler-01-0001）相等判定，弃 message 字符串包含。

### 幂等门语义（framework-idempotent，无 HTTP 面）
- complete 失败（P1-02）：不 release；记录维持 P 至 claimProcessingTtl；对调用方抛 `BizException(STORE_UNAVAILABLE / SYS-11-0003)`。
- DUPLICATE + findResult=null（P2-17）：抛 BizException（错误码按待确认②裁决：推荐 STORE_UNAVAILABLE/SYS-11-0003，备选 IN_FLIGHT/SYS-11-0002），绝不静默返回 null。
- 合法 null 结果（JSON "null" 字面量落库）回放保持 null，不受上述分支影响。

## 验收标准

- AC-1 given: scheduler 已注册一次性任务（jobKey=wf-timer:t1） when: 经真实 MVC 调用 DELETE /internal/v1/scheduler/jobs/wf-timer:t1（internal token） then: HTTP 200、Result.success=true、任务定义按 jobKey 软删（查无）、时间轮条目移除 → REQ20261127235505SchedulerJobDeregisterIntegrationTest#再注册代理腿（当前 RED=round1 打回项；时间轮移除=backend-dev 落实声明，契约确认⑦）
- AC-2 given: jobKey wf-timer:t1 已注销或从未注册 when: 再次 DELETE 同 jobKey then: HTTP 200、Result.success=true（幂等收敛，不抛 NOT_FOUND） → REQ20261127235505SchedulerJobDeregisterIntegrationTest#再注销幂等腿
- AC-3 given: 注销请求命中旧版 scheduler/路由缺失返回的 HTTP 404（非标准信封） when: SchedulerRegistrationClient.unregister 捕获解码异常 then: 按异常携带的 HTTP 状态码 404 判定收敛为幂等成功，不因消息字符串误判；非 404 失败照常上抛 → REQ20261127235505SchedulerRegistrationClientTest（404 收敛/500 上抛/无状态 BizException 上抛三腿）+ REQ20261127235505FeignErrorDecoderHttpStatusTest（状态码载体四腿）
- AC-4 given: 注册→注销→再注销全链（嵌入式 PG 集成测试，真实 MVC，禁 mock 假绿） when: 依序 POST 注册、DELETE、DELETE then: 三步全 success，再注销幂等；测试落新增独立测试类 → REQ20261127235505SchedulerJobDeregisterIntegrationTest#全链腿
- AC-5 given: action 执行成功但 store.markDone 抛 RuntimeException when: gate.execute 同 key 首次调用 then: 抛 BizException(STORE_UNAVAILABLE)、认领不释放（记录维持 P）、action 恰执行一次 → REQ20261127235505HttpIdempotentGateCompleteFailureTest#complete 失败腿
- AC-6 given: complete 失败后记录维持 P 且未过 claimProcessingTtl when: 同 token 重试 then: 得 BizException(IN_FLIGHT / SYS-11-0002)，不重执行（action 调用计数不变） → REQ20261127235505HttpIdempotentGateCompleteFailureTest#P 存续重试腿
- AC-7 given: 记录 s=D 但 findResult=null when: 同 key execute 走 DUPLICATE 分支 then: 抛 BizException(STORE_UNAVAILABLE / SYS-11-0003)，绝不静默返回 null → REQ20261127235505HttpIdempotentGateCompleteFailureTest#s=D 空结果腿
- AC-8 given: 业务合法返回 null（encode 产出 JSON "null" 字面量） when: 首次执行与重复执行 then: 首次返回 null、重复回放 null（不落入 AC-7 异常分支；合法 null 回放回归护栏） → REQ20261127235505HttpIdempotentGateCompleteFailureTest#合法 null 回放腿
- AC-9 given: action 抛异常 when: gate.execute then: 认领释放、同 token 重试可重执行（既有 failureReleasesClaimAndAllowsRetry 语义保持） → 既有 HttpIdempotentGateTest#failureReleasesClaimAndAllowsRetry + CompleteFailureTest 复证腿
- AC-10 given: tryBegin 抛异常 when: gate.execute then: STORE_UNAVAILABLE fail-closed（既有 storeFailureIsFailClosed 保持），既有 9 例单测全绿不回归 → 既有 HttpIdempotentGateTest#storeFailureIsFailClosed + CompleteFailureTest 复证腿
- AC-11 given: platform-scheduler-service / platform-workflow-service / framework-idempotent / framework-feign（确认⑥入批） when: 本机模块级 mvn test（`-pl <模块> -am -B test`） then: 全绿 0 FAIL（SKIP 逐条归因登记；不表述 lint） → {门禁台账，阶段三}

## 变更清单（差异契约）

- 新增/修改（后端 main）：
  - platform-scheduler-service：`InternalJobRegistrationController` 增 `@DeleteMapping("/internal/v1/scheduler/jobs/{jobKey}")`；`JobUseCase` 增 `deleteByJobKey(String)`（幂等软删，复用 kernel.removeWheel + repository.delete）；两处 Javadoc「未交付」表述更新为已交付。
  - platform-workflow-service：`SchedulerRegistrationClient.isNotFound` 改按 HTTP 状态码判定（载体=待确认①裁决产物）；类 Javadoc 同步收敛。
  - framework-idempotent：`HttpIdempotentGate` CLAIMED 分支拆分 action/complete 失败路径（complete 失败不 release）；`replay` 对 DUPLICATE+findResult=null 改抛错。
  - framework-feign（确认⑥方案 A）：`FeignErrorDecoder` 产物改投新增公共异常类型（support 包，`extends BizException`，携带 int httpStatus）；`RemoteErrorCode` 不动，既有 `BizException` catch 面不变（既有 FeignErrorDecoderTest 断言 isInstanceOf(BizException) 兼容子类，保持全绿）。
- 测试（新增独立测试类/用例）：
  - platform-scheduler-service：注册→注销→再注销集成契约测试（`EmbeddedPostgresSupport` 底座 + 真实 MVC + internal token）。
  - platform-workflow-service：SchedulerRegistrationClient 404 状态码判定单测（404 收敛 / 非 404 上抛）。
  - framework-idempotent：markDone 失败分支（AC-5/6）、DUPLICATE+null 分支（AC-7）、合法 null 回放护栏（AC-8）用例。
  - framework-feign（确认⑥方案 A）：解码产物状态码携带用例（新增独立测试类，含 404/非 404 状态保真断言）。
- 删除：无端点删除；`isNotFound` 与 `isDuplicateKey` 的 message 字符串匹配逻辑均被替换（确认⑥③）。
- 数据：无表变更（`migrations: {}`，无 Flyway）。
- 前端：无（internal 协议域不经网关、无 FE 面）。

## 边界与并行避让

- 禁改占用文件：B1/REQ-20261127-234335（platform-system RLS 相关）、B2b（business-leave / platform-auth / 网关适配层）、B3a（前端 mobile/web 及其在 platform-scheduler-service 的 wire 测试文件；B3a 仅新增测试文件、不动 scheduler main 源，与本批 main 改动无碰撞）。
- 本批测试落新增独立测试类，不编辑既有 REQ 命名测试文件（确认④）。
- 禁改任何错误码映射/新增错误码（B3b 独立后批；本批 DELETE 成功语义零新错误码）。
- isDuplicateKey 已裁决随批修（确认⑥③）：按结构化 errorCode 判定实现，禁止保留 message 字符串包含逻辑。
- 不做 git merge/push/删共享分支；不做 `.ai/` / `.ai-memory/` 写入；契约与委派单禁存凭证。
- 发现所需触碰文件与并行批次冲突：就地停止上报，禁止猜测。

## 待确认项（已于 2026-11-28 用户确认⑥全部裁决；下列留档结论）

1. **HTTP 状态码判定载体** → 已裁决：framework-feign 入批，方案 A——新增公共异常类型（support 包，`extends BizException` 携带 int httpStatus），`RemoteErrorCode` 不动。
2. **P2-17 异常语义** → 已裁决：DUPLICATE 且 findResult=null 抛 STORE_UNAVAILABLE（SYS-11-0003/503，fail-closed）。
3. **isDuplicateKey** → 已裁决：随批改结构化 errorCode 判定（`SchedulerErrorCodes.SCHEDULER_JOB_KEY_DUPLICATE="platform-scheduler-01-0001"` 锚点常量比较，弃 message 字符串包含）。
4. **DELETE 任务类型限制** → 已裁决：不限制仅 ONCE 类型（幂等软删任意 jobKey，internal token 保护域）。

## 流程状态（TL 每次行动后更新；这是唯一现场，跨会话据此恢复）

- phase: III-REVIEW       # 2026-11-28 五项门禁全 PASS；人工复核清单已交主线程，待用户确认+合并指令
- round: 1                # round1 delta 复验 PASS（收口）
- 门禁台账：
  - 1 三向一致: PASS（Feign SchedulerJobClient.deleteByJobKey = Controller @DeleteMapping
    `/internal/v1/scheduler/jobs/{jobKey}` 逐字一致；网关 application.yml:55 明文
    「/internal/v1/scheduler/** 不进网关路由白名单，服务间直连」且路由面仅 /admin/v1/scheduler/**
    ——internal 域不经网关，契约口径成立）
  - 2 AC 覆盖率: PASS（AC-1~AC-11 映射齐见验收标准节；AC-1 复验判据=
    REQ20261127235505SchedulerJobDeregisterIntegrationTest#should_soft_delete_registered_job_and_allow_reregister_when_deregister_called
    绿灯；AC-9/10 既有回归引用 HttpIdempotentGateTest#failureReleasesClaimAndAllowsRetry /
    #storeFailureIsFailClosed——该文件零改动已核验）
  - 3 契约机器层: PASS（node check-contract.mjs → [GATE] contract-valid PASS，status=confirmed）
  - 4 本地测试: PASS（采信+出处：backend-dev round1 自验 2026-11-28，TL 命令口径 mvn -pl <模块> -B test——
    platform-scheduler-service 40/40〔含 REQ20261127235505 全链 3/3 + 既有 4 类全绿〕、framework-feign 45、
    framework-idempotent 26〔含既有 9/9〕、platform-workflow-service 80，全部 0 失败 0 跳过 BUILD SUCCESS；
    约定=主线程合并前复核性重跑兜底，SKIP=0）
  - 5 跨需求冲突: PASS（本批 apis=POST/DELETE /internal/v1/scheduler/jobs + migrations:{}——
    B1=REQ-20261127-234335〔V37 platform-system-service/apis[]〕、B2b=REQ-20261127-235808
    〔admin leave 域/migrations{}〕、B3a=REQ-20261127-234158〔admin scheduler 域〕均不重叠；
    internal scheduler jobs 域全仓 contracts 仅本契约声明）
- 委派历史：
  - tester: 已交付——4 新增测试类（REQ20261127235505 前缀：SchedulerJobDeregisterIntegrationTest /
    SchedulerRegistrationClientTest / HttpIdempotentGateCompleteFailureTest / FeignErrorDecoderHttpStatusTest），
    AC-2~AC-10 映射齐（11 项场景 GREEN，含裁决③撞键结构化判定三腿反例）；AC-1 RED 判定为真实契约违约。
  - backend-dev: 打回(round 1)——delta 单「AC-1 再注册撞全列唯一键 500」。
  - backend-dev: 交付(round 1)——新增所有权 2 文件（SchedulerKernel 复活分支 + SchJobDefinitionMapper
    原生 @Select/@Update）；TL 复核逐条对齐打回单：原生 SQL 绕 Flex 逻辑删注入、WHERE AND deleted=true
    并发护栏、0 行命中→SCHEDULER_JOB_KEY_DUPLICATE 收敛（禁 INSERT 重试）、审计三件套 SQL 内显式
    （updated_at/updated_by/version=version+1；created_at/created_by 保留原始创建面）、
    buildOneShotDefinition INSERT/复活两路径语义同源+前置校验先行（非法请求零写面）、jsonb cast 装载、
    复活后回读+registerWheelQuietly+「复活」日志；round0 8 文件零改动、JobUseCase.create 管理面未动
    （git diff 单 hunk 核验）。复验=PASS。
- 升级记录：
  - [2026-11-28] AC-1 RED 裁决（leader，tester 证据两轮确定性复现 + TL 只读核验）：根因=注销为 Flex 逻辑删
    （AuditBase isLogicDelete，行物理保留）而 `V1__init_schema.sql:35` uk_sch_job_definition_key 为全列唯一
    → 再注册 INSERT 必撞；`SchedulerKernel.registerOneShot` 撞键检查被逻辑删自动过滤（deleted=false）看不到软删行
    → 500 SYS-00-0006。裁决=方向 A「复活软删行」，范围收窄至内部一次性注册路径（jobKey=wf-timer:{timerJobId}
    确定性键，注销→再注册是协议生命周期常态，复活即正确语义）；否决 B（偏离契约钉死「软删、执行历史保留」，
    且孤儿化 sch_job_execution.job_id 引用面）、否决 C（需迁移，migrations:{} 冻结，除非 A 证实不可行再经用户走范围变更）。
    同型既有隐患登记不修（不顺手修无关项）：管理面 JobUseCase.create 对「已删键重建」同样 500（先 admin 删后同键重建），
    待登记整改方案另批。
  - [2026-11-28] tester 知情项①：自验环境修复执行了 `mvn -pl acme-framework/framework-feign install -DskipTests`
    （.m2 快照早于 worktree 新增 RemoteCallException；flex-test-assembly 既定 .m2 刷新口径，非验证性跑测，
    不计入自验白名单违规，登记备查；后续同类需求先报 TL）。②机械勘误备案：ErrorCode 接口=2 抽象方法
    （code/defaultMessage；HttpStatusAware 为独立接口）；SchedulerRegistrationClient FQN=
    com.acme.platform.workflow.infrastructure.client（委派单原口径一致，无误导）。
