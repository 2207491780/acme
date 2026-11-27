---
requirementId: "REQ-20261127-235505"
title: "B2a 后端正确性·注销与幂等（P1-01/P1-02/P2-17）：scheduler DELETE 注销端点补齐 + 幂等门 complete 失败不释放"
status: draft              # 用户逐项确认后置 confirmed；未 confirmed 禁止进入 II-DEV
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

## API

### DELETE /internal/v1/scheduler/jobs/{jobKey}（新增，P1-01）
- 内部协议端点（internal token 保护域，不经网关对外；与既有 POST 同控制器）。
- 幂等：jobKey 不存在 → HTTP 200 + `Result<Void>` success=true（不抛 NOT_FOUND、无新增错误码）。
- 存在 → 软删（复用 `JobUseCase.delete` 语义：kernel.removeWheel + repository.delete，执行历史保留）→ HTTP 200 success。
- jobKey 不限制任务类型（默认口径，待确认④）；返回：`Result<Void>`（code="0"，data=null）。

### workflow 客户端语义（SchedulerRegistrationClient.unregister，无 HTTP 面）
- isNotFound 改按 HTTP 状态码判定（异常携带状态的载体见待确认①）：仅 404 收敛为幂等成功；其余失败照常上抛（TimerScheduler 告警不阻断本地清理，语义不变）。
- isDuplicateKey 字符串匹配保持现状不动（待确认③裁决前禁顺手改）。

### 幂等门语义（framework-idempotent，无 HTTP 面）
- complete 失败（P1-02）：不 release；记录维持 P 至 claimProcessingTtl；对调用方抛 `BizException(STORE_UNAVAILABLE / SYS-11-0003)`。
- DUPLICATE + findResult=null（P2-17）：抛 BizException（错误码按待确认②裁决：推荐 STORE_UNAVAILABLE/SYS-11-0003，备选 IN_FLIGHT/SYS-11-0002），绝不静默返回 null。
- 合法 null 结果（JSON "null" 字面量落库）回放保持 null，不受上述分支影响。

## 验收标准

- AC-1 given: scheduler 已注册一次性任务（jobKey=wf-timer:t1） when: 经真实 MVC 调用 DELETE /internal/v1/scheduler/jobs/wf-timer:t1（internal token） then: HTTP 200、Result.success=true、任务定义按 jobKey 软删（查无）、时间轮条目移除 → {用例名，tester 交付后回填}
- AC-2 given: jobKey wf-timer:t1 已注销或从未注册 when: 再次 DELETE 同 jobKey then: HTTP 200、Result.success=true（幂等收敛，不抛 NOT_FOUND） → {用例名}
- AC-3 given: 注销请求命中旧版 scheduler/路由缺失返回的 HTTP 404（非标准信封） when: SchedulerRegistrationClient.unregister 捕获解码异常 then: 按异常携带的 HTTP 状态码 404 判定收敛为幂等成功，不因消息字符串误判；非 404 失败照常上抛 → {用例名}
- AC-4 given: 注册→注销→再注销全链（嵌入式 PG 集成测试，真实 MVC，禁 mock 假绿） when: 依序 POST 注册、DELETE、DELETE then: 三步全 success，再注销幂等；测试落新增独立测试类 → {用例名}
- AC-5 given: action 执行成功但 store.markDone 抛 RuntimeException when: gate.execute 同 key 首次调用 then: 抛 BizException(STORE_UNAVAILABLE)、认领不释放（记录维持 P）、action 恰执行一次 → {用例名}
- AC-6 given: complete 失败后记录维持 P 且未过 claimProcessingTtl when: 同 token 重试 then: 得 BizException(IN_FLIGHT / SYS-11-0002)，不重执行（action 调用计数不变） → {用例名}
- AC-7 given: 记录 s=D 但 findResult=null when: 同 key execute 走 DUPLICATE 分支 then: 抛 BizException（错误码=待确认②裁决值），绝不静默返回 null → {用例名}
- AC-8 given: 业务合法返回 null（encode 产出 JSON "null" 字面量） when: 首次执行与重复执行 then: 首次返回 null、重复回放 null（不落入 AC-7 异常分支；合法 null 回放回归护栏） → {用例名}
- AC-9 given: action 抛异常 when: gate.execute then: 认领释放、同 token 重试可重执行（既有 failureReleasesClaimAndAllowsRetry 语义保持） → {用例名}
- AC-10 given: tryBegin 抛异常 when: gate.execute then: STORE_UNAVAILABLE fail-closed（既有 storeFailureIsFailClosed 保持），既有 9 例单测全绿不回归 → {用例名}
- AC-11 given: platform-scheduler-service / platform-workflow-service / framework-idempotent（及待确认①批准后的 framework-feign） when: 本机模块级 mvn test（`-pl <模块> -am -B test`） then: 全绿 0 FAIL（SKIP 逐条归因登记；不表述 lint） → {门禁台账}

## 变更清单（差异契约）

- 新增/修改（后端 main）：
  - platform-scheduler-service：`InternalJobRegistrationController` 增 `@DeleteMapping("/internal/v1/scheduler/jobs/{jobKey}")`；`JobUseCase` 增 `deleteByJobKey(String)`（幂等软删，复用 kernel.removeWheel + repository.delete）；两处 Javadoc「未交付」表述更新为已交付。
  - platform-workflow-service：`SchedulerRegistrationClient.isNotFound` 改按 HTTP 状态码判定（载体=待确认①裁决产物）；类 Javadoc 同步收敛。
  - framework-idempotent：`HttpIdempotentGate` CLAIMED 分支拆分 action/complete 失败路径（complete 失败不 release）；`replay` 对 DUPLICATE+findResult=null 改抛错。
  - framework-feign（待确认①）：`FeignErrorDecoder` 产物携带 HTTP 状态码——方案 A（推荐）：新增公共异常类型（support 包，`extends BizException`，携带 int httpStatus），RemoteErrorCode 与既有 catch(RuntimeException) 面不变；方案 B：RemoteErrorCode 增 httpStatus 分量。
- 测试（新增独立测试类/用例）：
  - platform-scheduler-service：注册→注销→再注销集成契约测试（`EmbeddedPostgresSupport` 底座 + 真实 MVC + internal token）。
  - platform-workflow-service：SchedulerRegistrationClient 404 状态码判定单测（404 收敛 / 非 404 上抛）。
  - framework-idempotent：markDone 失败分支（AC-5/6）、DUPLICATE+null 分支（AC-7）、合法 null 回放护栏（AC-8）用例。
  - framework-feign（随待确认①）：解码产物状态码携带用例。
- 删除：无端点删除；仅 `isNotFound` 字符串匹配逻辑被替换（isDuplicateKey 待确认③裁决前不动）。
- 数据：无表变更（`migrations: {}`，无 Flyway）。
- 前端：无（internal 协议域不经网关、无 FE 面）。

## 边界与并行避让

- 禁改占用文件：B1/REQ-20261127-234335（platform-system RLS 相关）、B2b（business-leave / platform-auth / 网关适配层）、B3a（前端 mobile/web 及其在 platform-scheduler-service 的 wire 测试文件；B3a 仅新增测试文件、不动 scheduler main 源，与本批 main 改动无碰撞）。
- 本批测试落新增独立测试类，不编辑既有 REQ 命名测试文件（确认④）。
- 禁改任何错误码映射/新增错误码（B3b 独立后批；本批 DELETE 成功语义零新错误码）。
- isDuplicateKey 字符串匹配属 plan 外同型反模式：待确认③裁决前禁顺手改。
- 不做 git merge/push/删共享分支；不做 `.ai/` / `.ai-memory/` 写入；契约与委派单禁存凭证。
- 发现所需触碰文件与并行批次冲突：就地停止上报，禁止猜测。

## 待确认项（未裁决不下发）

1. **HTTP 状态码判定载体（P1-01 前置，plan 涉及模块外一行 framework-feign）**：FeignErrorDecoder 现状丢弃 HTTP 状态，「isNotFound 改按状态码判定」需最小扩展。方案 A（推荐）：新增公共异常类型（framework-feign support 包，`extends BizException`，携带 httpStatus；RemoteErrorCode 不动，既有 BizException catch 面不变）；方案 B：`RemoteErrorCode` 增 httpStatus 分量（record 签名变更，引用不出 framework-feign）。请批准 A 或 B（即批准 framework-feign 进本批模块清单）。
2. **P2-17 异常语义二选一**：DUPLICATE 且 findResult=null 抛——推荐 STORE_UNAVAILABLE（SYS-11-0003/503，记录态异常 fail-closed 口径）；备选 IN_FLIGHT（SYS-11-0002/409，引导客户端稍后重试）。plan 原文两可，请定一个。
3. **isDuplicateKey 是否随批修**：同文件同型字符串匹配反模式（contains "platform-scheduler-01-0001"/"409"），plan 未列。推荐随批改为结构化 errorCode 判定（workflow 侧一行 + 用例，无需 framework 改动）；若坚守「不顺手修无关项」则登记整改方案另批。请定夺。
4. **DELETE 是否限制仅 ONCE 类型任务可注销**：推荐不限制（internal token 保护域、调用方仅 workflow、jobKey 约定 wf-timer:*；限制引入多余失败态）。默认不限制，如需收紧请明示。

## 流程状态（TL 每次行动后更新；这是唯一现场，跨会话据此恢复）

- phase: I-REQ            # I-REQ | I-DONE | II-DEV | III-GATE | III-TEST | III-REVIEW | DONE
- round: 0
- 门禁台账：未开跑（阶段一）
- 委派历史：暂无
- 升级记录：暂无
