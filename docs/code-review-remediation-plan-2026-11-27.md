# 代码评审整改方案（REVIEW-20261127-161433）

> 依据：[contracts/REVIEW-20261127-161433.md](../contracts/REVIEW-20261127-161433.md)（全量评审：47 条意见，P0×1 / P1×11 / P2×23 / P3×12，全部 `origin=historical`，结论 APPROVE）。
> 评审第 5 节要求「重大缺陷单列整改跟踪项」——**本方案即该整改跟踪的唯一入口**：批次划分、修复口径、验证门禁、决策点与勾销状态都在此维护；修复完成后逐条更新状态列，不另建第二份跟踪文件。
> 制定：2026-11-27；同日 owner 拍板决策点 ①~⑥（第 5 节），⑦待 L1 复现后定。执行遵循 `.ai/RULES.md` 与 `.ai-memory/playbooks/parallel-dev.md`；多文件业务改动经 `ai-coding-dev` 蜂群四阶段契约流程（engine 开关 auto）。

## 1. 输入摘要与总体判断

- **全部 47 条为历史债**，本轮工作树干净无新增阻塞。但其中含 1 条已确认的安全 Veto 机制缺陷（V-01 租户隔离可绕过）、11 条 P1 正确性/契约缺陷，均有实际业务影响，需按计划清偿而非无限期搁置。
- **两处门禁现状必须先处理**：① web 存在 1 个稳定失败用例（`REQ20261114071607-usersPage` 删除确认 15s 超时，串行复现非 flake）——不修复则任何前端批次都无法宣称「全量测试通过」；② 后端本机全量 `mvn test` 因 Testcontainers 超时不可完成——验证口径需改为「模块级本机 + CI 全量」双轨。
- **核心事实已抽样复核**（本方案制定时）：V33 RLS 明文口令与 `app.tenant_bypass` 自设旁路、scheduler 注销端点缺失（Feign 声明 DELETE 服务端仅 POST，Javadoc 自认「未交付」）、`AppTokenIssued` 字段名断层（`accessExpiresAt` vs 前端 `expiresAt`）、`Result.code()` 直拼全码、网关 7 条业务路由无 leave、**k8s base 实际只登记 5 个 platform 服务 + gateway（无 business-leave）**——评审证据可信，方案据此展开。

## 2. 排序原则

1. **先门禁后铺量**：失败用例与验证口径不清，后续每批的门禁结论都不可信。
2. **安全优先**：V-01 是唯一 P0（租户隔离可绕过），独立成批最先做。
3. **越权/幂等类正确性紧随其后**（P1-01/02/05/08/17）：它们是真实的数据暴露与重复执行风险，优先于「测试真实性」问题。
4. **wire 契约对齐先于依赖它的回归测试**（P1-06/07/10、P2-02）：当前 MSW 假绿、前端分支恒不命中，契约不修正则其他修复的测试结论失真；MSW 对齐（P2-16）尾随错误码定案之后。
5. **按模块切批避让冲突**：同一批不横跨会被并行会话同时持有的目录；前端批与后端批可并行。
6. **待证先核验**：带「待证」的项先做只读核验/产品确认，再定修法（见第 6 节）。
7. **重构后置**：重复代码治理（P2-18/19/20、P3-01/08/09）是纯结构收敛，放在行为修复之后，避免冲突放大。
8. **CI/L1 项移交不进代码批**：a11y/覆盖率/Sonar/慢查询基线/漏洞/密钥扫描/压测（见第 7 节）。
9. **迁移只前向**：已应用的 V33 不得回改（Flyway 校验和会破），一律新迁移修复；迁移号在 REQ I 阶段预分配（取号前 `git fetch` 防撞），本方案不预占号。
10. **与 2026-11-14 遗留清单去重**（`.ai-memory/drafts/review-deferred-items.md`）：重合项 P2-10（Nacos 鉴权）、P3-05（desktop dev 导航精确化）修复时两处同步勾销；P2-06 与遗留清单「单体 @SchedulerLock 失效」同域，合并为「单体形态收敛」任务。

## 3. 批次总览

| 批次 | 主题 | 覆盖意见（条数） | 规模 | 执行方式 | 依赖 |
|---|---|---|---|---|---|
| B0 | 门禁修复 | web 失败用例 + 验证口径（非 47 条内） | S | 单点小修（工作树，不走蜂群） | 无 |
| B1 | P0 安全：RLS 租户隔离 | V-01（1） | M | REQ 蜂群 | 待证① prod 暴露面 |
| B2a | 后端正确性·注销与幂等 | P1-01、P1-02、P2-17（3） | M | REQ 蜂群 | 无 |
| B2b | 后端正确性·越权与权限三态 | P1-05、P1-08（2） | M | REQ 蜂群 | 决策③已定 |
| B3a | wire 契约·DTO 字段 | P1-06、P1-10、P2-02（3） | M | REQ 蜂群（前后端两单） | 决策④已定 |
| B3b | wire 契约·错误码统一 | P1-07、P2-16（2） | L | REQ 蜂群 | 决策①已定 |
| B4 | 业务事务与消息可靠性 | P1-03、P1-04、P1-11、P2-12（4） | L | 拆 2 个 REQ | 无 |
| B5 | 可靠性加固（通知/审计/性能） | P2-01、P2-13、P2-14、P2-15、P2-21、P2-22（6） | M | 拆 1-2 个 REQ | 无 |
| B6 | 部署与发布收敛 | P1-09、P2-04~P2-11、P3-12（10） | M | 部署侧独立任务 + 决策 | 待证②③；决策②⑤已定 |
| B7 | 重复代码治理（重构） | P2-18、P2-19、P2-20、P3-01、P3-08、P3-09（6） | M | refactor 分支或 REQ | B2/B4/B5 落地后 |
| B8 | 前端细节与文档 | P3-02~07、P2-03、P2-23、P3-10（9） | S-M | 独立小修 / 随批 | 无（低优先） |
| CI | CI/L1 移交 | P3-11 及各「交 L1」项 | — | CI 任务单 | 随各批挂接 |

**建议节奏**：B0+B1 立即（可并行）→ B2a/B2b/B3a 三批并行（模块避让）→ B3b+B4 并行 → B5+B6 并行 → B7 → B8 收尾穿插。

## 4. 批次详情

### B0 门禁修复（S，立即）

- **修复 web 失败用例**：`web/tests/REQ20261114071607-usersPage.test.tsx > should_delete_row_after_confirm_when_row_delete_clicked` 15s 超时（评审 L1 串行复现，非并行 flake）。先只读归因（确认弹窗渲染时序 / MSW handler 延迟 / 等待条件），归因结论=用例错则修用例，=被测代码问题则修代码；禁止加长超时掩盖。
- **定验证口径**（写入各批门禁）：前端 `CI=true pnpm typecheck && CI=true pnpm test && CI=true pnpm build`（+ desktop `pnpm --filter @acme/desktop check`）；后端本机按模块 `"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd" -pl <模块> -am -B test`（Maven 不在 PATH，口径唯一权威 `.ai-memory/playbooks/flex-test-assembly.md`；原文案 `node run.mjs --mvn` 有误——acme-cloud 下无 run.mjs，2026-11-27 B1 leader 实测更正），**全量 mvn 交 CI**；原「本机 Testcontainers 超时」前提已被 8e717ef（零 Docker 嵌入式底座，22:38 入库）推翻——本机全量实测约 7 分钟可行（54 模块 2017 用例 0 跳过），SKIP 口径不变（如实登记 SKIP≠全量通过）。无统一 lint 脚本，禁止表述为 lint 通过。
- 验证：web 全量测试全绿（777/777）。
- 备注：B0 完成前，任何前端批次的「全量测试通过」结论都不可用。
- **结果（2026-11-27 复核）**：✅ 已关闭，零代码改动。串行复跑（6/6，删除用例 489ms）与并发分片全量（98 个测试文件、5 分片全部 exit=0）均绿。评审所记「串行稳定复现」的 15s 超时，根因为旧测试基建的 cssinjs 运行时样式编译（全局 role+name 查询样式计算过慢，测试文件头既有留档），已被评审入库（17:19）之后的提交 0c91f89（22:38，跳过 cssinjs + 共享 jsdom 分片）消除；测试文件与被测代码均无错误，无加长超时掩盖（`testTimeout: 15000` 系 round2 既有留档，非本次所加）。门禁口径基线实测全过：typecheck ✅ / test ✅ / build ✅ / desktop check ✅。上述备注解除，后续前端批次的「全量测试通过」结论恢复可用。

### B1 P0 安全：RLS 租户隔离（V-01）

**问题**（已复核）：`V33__rls_pilot.sql` 明文口令 `acme_rls_app/acme_rls_app_pwd` + GRANT 13 张含 tenant_id 表；RLS 策略以会话可自设的 `app.tenant_bypass='on'` GUC 作旁路判据——已知口令 + 可自设旁路 = 租户隔离可绕过。

**修复口径**：
1. **新迁移**（system 域，当前最新 V36，下一空号取号时以 contracts 预分配为准）：
   - `ALTER ROLE acme_rls_app`（决策⑥已定）：prod 形态 `NOLOGIN`（角色不可登录，已知口令随之失效）；test/dev 保留 `LOGIN`，口令用 test 联调值经配置注入（供 PG IT 铁证用例直连，不再硬编码）。
   - **策略旁路判据改造**：`app.tenant_bypass` 不得单独生效——绑定可信主体（如 `pg_has_role(current_user, <可信角色>, 'member')` 联合判定）或改为 SECURITY DEFINER 判定函数；具体设计在 REQ I 阶段钉死（涉及 TenantBypassScope 框架侧调用链联动）。
2. **不改已应用的 V33**（校验和会破，已部署环境的角色口令由新迁移重置）；V33 保持原样，新迁移注释说明修复关系。
3. 同步改 `REQ202611251737RlsNonSuperuserIntegrationTest`：角色口令从配置读取，不再硬编码。
4. `BACKEND.md:20-21` 生产环境变量覆盖要求同步核对更新。
5. 交 L1：密钥扫描（新增任务，见第 7 节）。

**验证**：RLS 集成测试新增「自设 GUC 旁路失效」用例；模块级 `mvn -pl platform-system -am test`；迁移幂等重放用例。
**前置待证①**：prod PG 端口外部暴露面（部署侧只读核验：Service/NetworkPolicy/防火墙口径）。
**规模**：M。

### B2a 后端正确性·注销与幂等（P1-01、P1-02、P2-17）

- **P1-01 scheduler 注销端点缺失**（已复核，Javadoc 自认未交付）：`InternalJobRegistrationController` 补 `@DeleteMapping("/internal/v1/scheduler/jobs/{jobKey}")`（幂等：不存在返回成功语义）；workflow 侧 `SchedulerRegistrationClient` 的 isNotFound 改按 HTTP 状态码判定（禁字符串匹配吞 404）；补「注册→注销→再注销幂等」契约测试。消除孤儿任务到点触发的逆向残留。
- **P1-02 + P2-17 幂等门**：`HttpIdempotentGate` 区分 action/complete 失败——complete() 写缓存失败**不 release 认领**、保留 P 至 TTL；DUPLICATE 且 `findResult=null` 返回 IN_FLIGHT/STORE_UNAVAILABLE 语义而非静默 null；补 markDone 失败分支单测（现有用例只覆盖 tryBegin 失败）。
- 涉及模块：platform-scheduler、platform-workflow（客户端侧）、framework-idempotent。
- 验证：三模块模块级 mvn test + 新增失败分支用例。

### B2b 后端正确性·越权与权限三态（P1-05、P1-08）

- **P1-05 请假单越权读取**（决策③已定：本人 + 授权角色）：`LeaveRequestUseCase` 详情按 `(tenant_id, user_id, id)` 过滤 + 授权角色放行（owner 或具备请假查看权限的管理员/审批人可读，契约补细粒度注解）；事件 businessKey 解析以 instanceId 为唯一锚点，删 `Long.parseLong` 数字兜底写单。
- **P1-08 权限查询异常口径**：抽公共 AuthorizationGateway 统一 fail-closed（auth 版补 try/catch，异常→契约化 403 而非 500）；下游权限查询改三态 TRUE/FALSE/UNKNOWN，UNKNOWN 不写 REJECTED 终态（当前查询异常→false→直接写终态）。
- 涉及模块：business-leave、platform-auth、四域网关适配层。
- 验证：越权用例（403/查无）、权限服务故障注入用例（UNKNOWN 不落终态）。

### B3a wire 契约·DTO 字段（P1-06、P1-10、P2-02）

**原则（决策④已定）**：以**后端现有 wire 序列化为权威**修前端，不用 `@JsonProperty` 别名固化错误契约（评审已确认无别名/命名策略，序列化即声明）；不加 `@JsonAlias` 过渡，未来出现旧客户端兼容需求再评估。

- **P1-06**（已复核）：mobile 四处（`api/auth.ts`、`store/authStore.ts`、`api/client.ts`、`screens/LoginScreen.tsx`）改 `accessExpiresAt`/`refreshExpiresAt`（当前 `expiresAt` → `Date.parse(undefined)=NaN`，过期判定/轮换全错）；`userId` 声明 string（`LongToStringModule` 已字符串化雪花 ID）。
- **P1-10**：scheduler 任务创建/编辑统一 `cronExpr`/`fixedRateMs`（前端 `scheduler.ts` + MSW 同步改长名）；补真实序列化契约测试（Java DTO ↔ TS 声明对照断言），禁再靠 mock 假绿。
- **P2-02**：`AdminMenuView` 前端 `id`/`parentId` TS 类型改 string（`LongToStringModule` wire 即字符串，前端声明 number 自相矛盾）；根节点 `0` 用 `'0'`/null 明确；补 wire 测试。
- 验证：mobile/web typecheck + 契约测试 + 受影响页面测试；契约测试须断言真实 Java DTO 序列化产物（或等价 golden 文件），非 MSW。

### B3b wire 契约·错误码统一（P1-07、P2-16）

- **方向（决策①已定）**：**全链路统一全码**（`platform-workflow-01-0003` 形态）——后端 `Result.code()` 不动，前端错误模块（`workflow.ts`、`system-errors.ts`、`notify-errors.ts`）的符号短码映射、i18n key、`UsersPage/TenantsPage/ProfilePage/SettingsScreen` 分支、MSW 码值全部升全码（「网关统一降符号码」方案已否决）。
- 改动面大（core 错误模块 + web 3 页面 + mobile SettingsScreen + MSW），**一批内完成**，避免两套码长时间并存；分支恒不命中（`workflowValidationErrorsOf()` 恒空、改密/租户分支失效）随之修复。
- **P2-16 MSW 语义对齐**（尾随本批）：publish/offline 幂等（version 不再每次 +1）、publish fanout sys_message、update 拒 PUBLISHED、terminate 允许 SUSPENDED→TERMINATED、错误码用全码——对齐 `NoticeUseCase`/`TerminateInstanceCommand` 真实状态机。
- 验证：错误码全码清单快照测试（后端 ErrorCode 枚举 ↔ 前端映射表）；受影响分支的前端用例从恒不命中改为真命中。

### B4 业务事务与消息可靠性（P1-03、P1-04、P1-11、P2-12）

拆两个 REQ：**B4-事务**（leave 域）与 **B4-消息**（stream/workflow 域）。

- **P1-03 apply() 事务化**：本地事务包 `save(APPLYING)`，流程启动移 `afterCommit`（TransactionSynchronization）；启动失败单据不落死态——入补偿表/对账任务或标记 RETRY 由后台重试；补「启流程失败」集成用例。
- **P1-04 ServiceTask 轮询**：去 `Thread.sleep(200ms)`×25 轮询占 Servlet 线程——改事件驱动（流程事件回调推进 APPROVING）或异步化；超时不写 FILED 终态，走重试/失败态（当前超时照写 FILED 覆盖状态机守卫）。交 L1 压测。
  - **口径演进（2026-11-28，REQ-20261128-092210 阶段一定案，经用户「继续」确认推荐口径）**：主线实测单体生产态 `acme.stream.enabled=false` → TASK_COMPLETED 事件永不消费，APPROVING 事件腿不存在——按字面「超时走重试」将使每单备案必重试耗尽挂起、试点流程全断。定案改为**下发权威推进**：删 `awaitApproving` sleep 轮询与超时写 FILED，APPLYING 收 ServiceTask 下发即视为审批办结权威证据，两步前向 APPROVING→FILED（与 `onTaskCompleted` 同义同守卫）；引擎侧重试/挂起仅承接真实瞬时故障。方案两条本意（去轮询占线程、超时不覆盖状态机守卫）均满足。
- **P1-11 DLQ 补全**：分布式形态补 leaveEventIn binding + DLQ topic；全仓补 DLQ 消费落库 + 告警通道 + 重放任务（当前仅出站声明、发送失败仅 log.warn）；单体惰性开关语义修正（bootstrap `:33/:38/:94` 关闭 function/stream 不生效）；双形态 binding 下发面文档化。
  - **范围拆分（2026-11-28，REQ-20261128-183425 定案）**：DLQ 消费落库/告警通道/重放任务与分布式 leaveEventIn+DLQ binding **拆分至 P1-09 上分布式栈专项**——决定性事实=leave 单体试点（决策②）+ prod stream 关闭下 DLQ 设施无任何运行表面，实现即死代码；本批交付可达面=①单体惰性开关语义修正（`spring.cloud.stream.enabled` 非有效开关的语义澄清 + 真实关闭机制=条件化函数 bean 缺席实证）②双形态 binding 下发面文档化（ENV-STACK 双形态节对照表）。DLQ 消费/告警/重放随上栈专项与 business-leave application.yml/网关路由/注册一并交付（P1-09 行已有登记）。
- **P2-12 消息顺序**：`WorkflowEventEnvelope` 加 partition key（businessKey/instanceId）；afterCommit 派发弃公共 ForkJoinPool 共享单线程 executor（按 key 串行）；消费侧终态保护（INSTANCE_COMPLETED 不压中间态——版本/状态机守卫）。交 L1 压测。

### B5 可靠性加固（P2-01、P2-13、P2-14、P2-15、P2-21、P2-22）

- **P2-13 WS 通知断线对账**：客户端重连/visibility 恢复时 REST 拉取 unread 对账（扩展既有 refreshUnread 收敛先例至重连时机）；服务端 pub/sub 不持久化的丢帧风险据此收敛。
- **P2-14 WS 握手**：token 从 `?token=` query 挪 header（代理/访问日志可记）；`connect` 无 token 静默返回改排重连；isAuthed 翻转后补就绪重连（建连时会话未就绪→永久不连）。
- **P2-15 refresh 超时护栏**：`renewOnce` 内部 refresh fetch 加超时/AbortController（对齐 guardedFetch 15s），防 401 重试路径无限悬挂。
- **P2-01 用户列表 N+1**：roleIdsOf 批量化（`findByUserIdIn` 分组），单页最多 201 次查询→常数次；补查询次数断言用例。交 L1 慢查询基线。
- **P2-21 审计脱敏**：`AuditContentTruncator` 对 before 旧值 JSON 递归脱敏（当前仅键名匹配，JSON 内 password/token 不脱敏）；补断言测试。
- **P2-22 消费跳过标记**：`ConsumeChannelInterceptor` ThreadLocal STATE 改消息头/显式上下文传递，不依赖同线程假设；并发派发探针用例。

### B6 部署与发布收敛（P1-09、P2-04~P2-11、P3-12）

部署侧为主（env-stack / CI），多数无法本地验证，按独立任务执行；决策②⑤已拍板，余待证项见第 6 节。

- **P1-09 leave 入口**（决策②已定：标注仅单体试点）：**实测 k8s base 仅登记 5 个 platform 服务 + gateway，无 business-leave**——「不可达」根因是业务模块未上分布式栈。按已定口径执行显式化：前端 base URL 约束（leave 请求仅走单体形态端点）+ 部署/总览文档标注「leave 仅单体试点」+ 契约注记；**不补网关路由与分布式 Deployment**，后续上栈另立专项（届时补路由 + 注册 + business-leave `application.yml`——当前该文件缺失为评审基线缺口）。
- **P2-05 部署顺序**：`deploy.sh` 先 seed-nacos 再 apply（当前 apply 早于 seed，`spring.config.import: nacos:*` fail-fast → CrashLoopBackOff 吃掉 1800s rollout 窗口）；回滚守卫「不得删 Nacos data-id」注释化/检查化。
- **P2-06 单体形态收敛**：monolith overlay 补删 platform-scheduler/platform-workflow 两 Deployment（当前漏删→双 tick/双 BPM 引擎/双 Flyway 写同 schema）；**与遗留清单「单体 @SchedulerLock 失效」合并一个任务**（锁 provider 收敛单 bean，两锁表语义差异在 REQ 阶段裁决）；反向回滚同理补对称性。
- **P2-04 release 版本**：前置待证② L1 build 复现（enforcer requireReleaseDeps × 硬编码 `1.0-SNAPSHOT`）；修法 `revision` 统一 CI 注入（flatten/enforcer 豁免机制在复现后定）。
- **P2-07 V34 回滚预案**：灰度期约束写入运维文档（禁插跨租户同名用户 or 数据清理脚本预案）；迁移本身不改（只前向）。
- **P2-08 撤销降级**：prod 网关撤销检查 `on-error` 改 FAIL_CLOSE（Redis 抖动期已撤销令牌继续可用不可接受）；回滚前提文档化。
- **P2-09 灰度能力**（决策⑤已定：移除/标注 experimental）：移除 `X-Acme-Gray` 注入等误导性配置，或明确标注 experimental 未生效（单 Deployment + Path 谓词无消费者）；真灰度（header 谓词 + 灰度池双 Deployment + 回退路径）另立专项，不混入 B6。
- **P2-10 Nacos 鉴权**（遗留清单重合，部署侧专项）：开 `NACOS_AUTH_ENABLE` + seed 脚本带 token；本地不可验证，随 B6 部署侧任务走，两处清单同步勾销。
- **P2-11 Redis ACL 基线**（部署侧）：ACL 用户规则模板/运维文档补齐，证实 DB index 由 ACL 强制。
- **P3-12 单体 SLA 口径**：文档化 `Local*Client` 直调无熔断/超时的边界与适用形态。
- 验证：k8s 清单 diff 审查 + 部署侧演练（生产操作按 conversational-cd 手册与授权分级，不在本方案展开）。

### B7 重复代码治理（P2-18、P2-19、P2-20、P3-01、P3-08、P3-09）

纯结构收敛（无行为变化），放 B2/B4/B5 之后，避免与行为修复冲突；每项保持公共层语义与被替换处逐字节等价（QR 哈希口径）。

- **P2-18**：`JsonbTypeHandler`×3（方法体哈希全一致）上提 framework 公共 TypeHandler，三域改引用。
- **P2-19 + P2-20 + P3-08**：`RequirePermissionInterceptor`×4 抽公共 Abstract 拦截器 + `PermissionDecider` 策略（三域逐字节一致上提，system 本地判定保留为特化策略）；`RequirePermission` 注解×4 三份上提公共契约、`@Target` 统一 `{METHOD, TYPE}`（P3-08 类级死分支随之消解——补类级生效测试或删兜底，REQ 阶段二选一）。
- **P3-01**：mobile `ApiEnvelope`/`QrTicketStatus`/`QrTicketInfoView` 改从 `@acme/core` 导入重导出（`AuthSession` 为模块特化不动）。
- **P3-09**：`ActionCount`×2 补镜像锁定单测（对齐 `ServiceTaskDispatchPayload` 的 `REQ20261122233425ServiceTaskPayloadMirrorTest` 先例）；上提 common-contract 作备选。
- 验证：模块级 mvn test + 前端 typecheck/test；行为零变化（无新用例要求，既有全量回归即门禁）。

### B8 前端细节与文档（P3-02~07、P2-03、P2-23、P3-10）

低优先机械项，可独立小修或随相近页面迭代顺手处理（RULES：不顺手改无关代码——「随批」仅限同文件同页）：

- **P3-02** mobile `apiFetch` 复用 core 15s 超时护栏（可覆盖超时）。
- **P3-03** `notifyStore` messageId Set 去重（重复帧虚增徽标）。
- **P3-04** core `client.ts` 续约失败跳转抽 `redirectToLogin` 平台适配（裸 `window.location.hash` 无 typeof window 守卫，框架无关层宿主假设）。
- **P3-05** desktop dev 导航改 `new URL(url).origin === new URL(devServerUrl).origin` 精确比对（前缀匹配可被 `localhost:5173.evil.com` 绕过；遗留清单重合项）。
- **P3-06** LoginScreen 验证码 effect 补 active 守卫（对齐 captcha-policy effect 先例，切租户旧响应覆盖新验证码）。
- **P3-07** QrConfirmScreen `handledRef` 进 loadInfo 即置位 + 失败分支节流（失败时连续扫码帧重复发起 qrInfo）。
- **P2-03** 租户 configKey 策略表/枚举单一登记（两处 switch 硬编码双改漏改风险）。
- **P2-23** UI-SPEC §0/2.1/§8 更新为 theme.ts 现状 12 色 hex/ON_ACCENT 表 + 重跑 `pnpm ui:guide`（工程文件非 .ai 派生，可直接改）。
- **P3-10** `MonolithFlywayConfig` javadoc 改「以各目录最新版本号为准」（陈旧 V27/V18/V1 实为 V36/V19/V3）。

## 5. 决策点（owner 2026-11-27 拍板 ①~⑥；⑦待 L1 复现后定）

| # | 决策 | 影响批次 | 结论（owner 拍板） |
|---|---|---|---|
| ① | 错误码统一方向：全码 vs 网关降符号码 | B3b | ✅ **全链路统一全码**：后端 Result 不动，前端错误映射/i18n/页面分支/MSW 一批升全码（网关降码方案否决） |
| ② | leave 入口形态：上分布式栈 vs 单体试点标注 | B6/P1-09 | ✅ **标注仅单体试点**：前端 base URL 约束 + 文档 + 契约注记显式化；后续上分布式栈另立专项 |
| ③ | 同租户请假单可见范围：仅本人 vs 管理员可见 | B2b/P1-05 | ✅ **本人 + 授权角色**：owner 或具备请假查看权限的管理员/审批人可读；契约补细粒度注解 |
| ④ | DTO 命名权威：后端 wire 为准改前端 vs @JsonAlias 过渡 | B3a | ✅ **后端为准改前端**：不加 @JsonAlias（未来出现旧客户端兼容需求再评估过渡） |
| ⑤ | 灰度能力：补真灰度池 vs 移除误导配置 | B6/P2-09 | ✅ **移除/标注 experimental**：真灰度另立专项 |
| ⑥ | V-01 口令管理载体：placeholder+密钥管理 vs prod NOLOGIN | B1 | ✅ **prod NOLOGIN + test 联调值**：新迁移 prod 形态 ALTER ROLE NOLOGIN、test 保 LOGIN 供 PG IT；旁路判据另绑可信主体 |
| ⑦ | P2-04 release 机制：flatten vs enforcer 豁免 | B6 | 待 L1 复现后定（复现后回填本表） |

## 6. 待证清单（修复前置只读核验）

| # | 待证项 | 关联 | 核验方式 |
|---|---|---|---|
| ① | prod PG 端口外部暴露面 | V-01 | 部署侧只读核验（Service/NetworkPolicy/防火墙口径） |
| ② | release SNAPSHOT 阻断复现 | P2-04 | L1 build 实跑（本机或 CI） |
| ③ | prod 数据中跨租户同名用户存量 | P2-07 | prod 只读查询（按授权分级与 prod 只读纪律） |
| ④ | 双 tick/双 Flyway 后果实证 | P2-06 | monolith 形态日志归因（与遗留清单 @SchedulerLock 证据合并） |
| ⑤ | Nacos 无鉴权可达性 | P2-10 | 部署侧网络核验 |
| ⑥ | Redis ACL 是否强制 DB index | P2-11 | 运维侧核验 |
| ⑦ | 单体承接真实流量的 SLA 口径 | P3-12 | 产品/运维确认 |

## 7. CI/L1 移交项（不进代码批）

- 密钥扫描（V-01 建议新增任务；当前不在锁文件，禁新增依赖——用 CI 侧既有能力如 gitleaks 需先核对 project.md 登记流程）。
- 慢查询基线（P2-01）、L1 压测（P1-04、P2-12）、漏洞扫描、覆盖率、Sonar、axe a11y、文档格式——评审已标注「无既有工具/不在锁文件」，逐项走 CI 任务单与依赖登记流程，不随代码批夹带。
- **P3-11**：`ExcelImportButton`/`ImportXmlModal` 硬编码 hex 兜底与中文 JSX → CI 抽样治理项。

## 8. 执行流程（本仓库工作流）

1. **开工**：多文件业务改动（B1~B5、B7）经 `ai-coding-dev` 蜂群四阶段契约：/req 立契约（I-REQ 预分配迁移号，取号前 `git fetch`）→ `node .ai/worktree.mjs add feat/req-<...>` → 并行委派 → 门禁 → 用户复核 → 合并授权。单点小修（B0、B8 各项）直接工作树 `fix/<slug>` 分支。
2. **门禁**：前端三件套 + desktop check；后端模块级 `-pl <模块> -am` + CI 全量；触 `.ai/`/`.ai-memory/` 的批先读 `ai-config.md` 五步流程并 `node .ai/sync.mjs --check`；本方案（docs/）无派生关系。
3. **合并**：回主树优先快进（注意：主树未跟踪同名文件会挡快进，同源先删再 merge；快进后 `sync --check` 报 localOnly 假红先跑一次 `node .ai/sync.mjs` 刷新）；合并后跑受影响端门禁。
4. **推送**：`git push origin acme-foundation ai-coding-support` 一条命令双分支同推（镜像 AI 路径逐字节一致为前提；fix/feat 默认不推远端）。
5. **勾销**：每条意见修复合并后更新第 10 节状态列（`未开始 → 已修复(批次/REQ) → 已验证`）；与遗留清单重合项同步勾销 `.ai-memory/drafts/review-deferred-items.md`。
6. **纪律**：一次提交一件事；生成代码与格式化分开；不顺手修无关项（发现新问题登记回本方案或评审轮次）；测试失败如实报告。

## 9. 风险与注意事项

- **Flyway 只前向**：V33/V34 不回改；新迁移幂等口径照 V30/V31 先例（IF NOT EXISTS / DROP POLICY IF EXISTS）。
- **B3b 错误码改动面大**：一批完成、全码快照测试锁死，防止两套码并存；MSW（P2-16）必须在错误码定案后同步，否则假绿回归。
- **契约测试禁止 mock 假绿**：B3 系列的验证必须断言真实 Java DTO 序列化产物或等价 golden。
- **B7 重构等价性**：以方法体哈希一致为上提前提，公共层落地后被替换处逐字节等价；任何行为差异即回退重构而非改语义。
- **部署侧操作**（B6）：生产环境操作按 prod 只读纪律与授权分级执行，方案不授予任何 prod 写权限。
- **并行避让**：`.ai/`/`.ai-memory/` 同一时刻仅一个会话持有；前后端批次并行时按模块目录切分（B2 后端 ↔ B3 前端可并行；B2a/B2b 亦可并行）。

## 10. 状态跟踪表（47 条全量映射）

> 状态：`未开始` → `已修复(批/REQ)` → `已验证`。修复合并后逐条更新；推翻本表口径需在正文标注。

| 意见 | 批次 | 修复口径摘要 | 状态 |
|---|---|---|---|
| V-01 | B1 | 新迁移重置角色口令（placeholder/NOLOGIN）+ 旁路判据绑定可信主体 + 密钥扫描 | 已修复(B1/REQ-20261127-234335，69866cd) |
| P1-01 | B2a | 补 DELETE 注销端点 + isNotFound 状态码化 + 逆向契约测试 | 已修复(B2a/REQ-20261127-235505，d16175e) |
| P1-02 | B2a | complete 失败不 release、保留 P 至 TTL + 失败单测 | 已修复(B2a/REQ-20261127-235505，d16175e) |
| P2-17 | B2a | DUPLICATE findResult=null 返回 IN_FLIGHT/STORE_UNAVAILABLE | 已修复(B2a/REQ-20261127-235505，d16175e) |
| P1-05 | B2b | 详情 (tenant_id,user_id,id) 过滤 + 授权角色放行 + businessKey 以 instanceId 为锚（决策③已定：本人+授权角色） | 已修复(B2b/REQ-20261127-235808，56032b5) |
| P1-08 | B2b | 公共网关 fail-closed + 权限三态、UNKNOWN 不写终态 | 已修复(B2b/REQ-20261127-235808，56032b5) |
| P1-06 | B3a | mobile 四处改 accessExpiresAt/refreshExpiresAt + userId string + wire 测试 | 已修复(B3a/REQ-20261127-234158，64ef965) |
| P1-10 | B3a | cronExpr/fixedRateMs 统一 + MSW 同步 + 契约测试（决策④已定：后端为准） | 已修复(B3a/REQ-20261127-234158，64ef965) |
| P2-02 | B3a | AdminMenuView id/parentId TS 改 string + 根节点语义 | 已修复(B3a/REQ-20261127-234158，64ef965) |
| P1-07 | B3b | 错误码全码统一 + i18n/页面分支/MSW + 全码快照测试（决策①已定：全码） | 已修复(B3b 8a51fae) |
| P2-16 | B3b | MSW 幂等/fanout/状态机/码值对齐后端 | 已修复(B3b 8a51fae) |
| P1-03 | B4 | apply 事务化 + afterCommit 启流程 + 补偿/重试 | 已修复(B4-事务 db93c80；含 round-1 租户死面修复) |
| P1-04 | B4 | 去 Thread.sleep 轮询、事件驱动、超时不写终态 | 已修复(B4-事务 db93c80；口径演进=下发权威推进，见 §4 标注) |
| P1-11 | B4 | DLQ 消费/告警/重放 + 双形态 binding 下发面 | 未开始 |
| P2-12 | B4 | partition key + 串行派发 + 消费侧终态保护 | 未开始 |
| P2-01 | B5 | 角色批量查询消 N+1 + 查询次数断言 | 未开始 |
| P2-13 | B5 | WS 断线重连 REST 对账 | 未开始 |
| P2-14 | B5 | token 挪 header + 排重连 + 就绪重连 | 未开始 |
| P2-15 | B5 | refresh 超时护栏 | 未开始 |
| P2-21 | B5 | 审计 before JSON 递归脱敏 + 断言 | 未开始 |
| P2-22 | B5 | 消费跳过标记去 ThreadLocal + 并发探针 | 未开始 |
| P1-09 | B6 | 标注仅单体试点显式化：前端 base URL 约束 + 文档 + 契约注记（决策②已定；实测 base 无 business-leave） | 已修复(B6 ab0d58e：前端约束+文档；Controller 契约注记待 B4-事务 合流后补) |
| P2-04 | B6 | revision CI 注入（前置待证② L1 复现） | 未开始 |
| P2-05 | B6 | deploy.sh 先 seed 后 apply + 回滚守卫 | 已修复(B6 ab0d58e：两阶段部署+回滚守卫) |
| P2-06 | B6 | monolith 补删 scheduler/workflow + 锁 provider 收敛（合并遗留清单） | 部分修复(B6 ab0d58e：清单补删 ✅；锁 provider 收敛另立任务) |
| P2-07 | B6 | 灰度回滚数据预案文档（前置待证③） | 已修复(B6 ab0d58e：灰度回滚数据预案文档) |
| P2-08 | B6 | prod 撤销检查 FAIL_CLOSE | 已修复(B6 ab0d58e：FAIL_CLOSE 参数化+prod 注入) |
| P2-09 | B6 | 移除/标注 experimental（决策⑤已定） | 已修复(B6 ab0d58e：experimental 标注；真灰度另立专项) |
| P2-10 | B6 | Nacos 鉴权 + seed 带 token（重合遗留清单，部署侧） | 已修复(B6 ab0d58e：配置/脚本/文档；实际开启=运维三步) |
| P2-11 | B6 | Redis ACL 基线文档 | 已修复(B6 ab0d58e：ACL 基线文档；待证⑥留核验) |
| P3-12 | B6 | 单体 SLA 口径文档 | 已修复(B6 ab0d58e：单体 SLA 口径文档) |
| P2-18 | B7 | JsonbTypeHandler 上提 framework | 未开始 |
| P2-19 | B7 | RequirePermissionInterceptor 抽 Abstract + 策略 | 未开始 |
| P2-20 | B7 | RequirePermission 注解三份上提 | 未开始 |
| P3-08 | B7 | @Target 统一 METHOD+TYPE（随 P2-20） | 未开始 |
| P3-01 | B7 | mobile 跨端声明改从 @acme/core 重导出 | 未开始 |
| P3-09 | B7 | ActionCount 镜像锁定单测 | 未开始 |
| P3-02 | B8 | mobile apiFetch 超时护栏 | 已修复(B8 5d087f6) |
| P3-03 | B8 | notifyStore messageId 去重 | 已修复(B8 5d087f6) |
| P3-04 | B8 | redirectToLogin 平台适配 | 已修复(B8 5d087f6) |
| P3-05 | B8 | desktop dev 导航精确 origin（重合遗留清单） | 已修复(B8 5d087f6) |
| P3-06 | B8 | 验证码 effect active 守卫 | 已修复(B8 5d087f6) |
| P3-07 | B8 | handledRef 置位时机 + 失败节流 | 已修复(B8 5d087f6) |
| P2-03 | B8 | configKey 策略表单一登记 | 已修复(B8 5d087f6) |
| P2-23 | B8 | UI-SPEC 12 色对齐 + ui:guide 重跑 | 已修复(B8 5d087f6) |
| P3-10 | B8 | MonolithFlywayConfig javadoc 更新 | 已修复(B8 5d087f6) |
| P3-11 | CI | 交 CI/L1 抽样治理 | 未开始 |

## 11. 新发现登记（整改执行期，2026-11-28 起）

> 执行批次时新发现、超出原 47 条清单的缺陷。按纪律「不顺手修无关项」，逐条登记另批处理。

| # | 发现来源 | 现象 | 建议方向 | 状态 |
|---|---|---|---|---|
| N-01 | REQ-20261127-235505（B2a）round1 裁决 | 管理面 `JobUseCase.create` 对「已软删同 jobKey 重建」同样触发 `DuplicateKeyException` → HTTP 500（uk_sch_job_definition_key 全列唯一 + 逻辑删过滤同源）；admin 先删后建即复现 | 与 B2a 内部注册路径同型（复活软删行 / 管理面语义裁决），需独立 REQ；common-test 候选改进 `EmbeddedPostgresSupport` initdb `--locale=C` 一并评估 | 未开始 |
