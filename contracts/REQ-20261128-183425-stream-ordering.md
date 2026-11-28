---
requirementId: "REQ-20261128-183425"
title: "B4-消息 消息可靠性·stream/workflow 域（P2-12 消息顺序 + P1-11 开关语义修正与 DLQ 拆分登记）：分区键保序 + afterCommit 直派 + 消费乱序回归锁"
status: confirmed          # 2026-11-28 主线程代拟并按用户既定「继续=推荐口径」模式确认（用户指令：并发受限，后续工作全主线程完成）；DLQ 运行面拆分见「P1-11 拆分说明」
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-20261128-183425-stream-ordering"
migrations: {}             # 本批无 Flyway（DLQ 落库表随 P1-09 上栈专项拆分，见 P1-11 拆分说明）
apis: []                   # wire 载荷零变更（WorkflowEventEnvelope 字段不动）；传输面新增分区键头+绑定 producer 配置，非 HTTP API
---

# B4-消息 消息可靠性·stream/workflow 域（P2-12 + P1-11 可达面）

## 背景

整改批次 B4-消息（`docs/code-review-remediation-plan-2026-11-27.md` §4 P1-11/P2-12 行 + §9 风险；评审证据 `contracts/REVIEW-20261127-161433.md` P1-11/P2-12）。钉死事实全部基于合并后主线（B1/B2a/B2b/B3a/B4-事务 已合流，tip 7df4f6f）复核（2026-11-28）：

1. **发布器已有专属单线程 executor**：`KafkaWorkflowEventPublisher.publish()`（`kernel/KafkaWorkflowEventPublisher.java:55-59`）内部 `ScheduledThreadPoolExecutor(1, "acme-workflow-event-publisher")` 异步发送——评审所述「公共 ForkJoinPool」残留在 `EventPublishInterceptor.afterCommit` 的 `CompletableFuture.runAsync` 包装（`:44-49`）：runAsync 在 commonPool 线程执行 publish 提交，多命令并发提交时**入队次序不定**（评审 P2-12 主证据链成立）。
2. **信封无分区键**：`WorkflowEventEnvelope`（api dto，9 字段 record）与 `EventEnvelope`（stream 信封：messageId/幂等键/traceId）均无分区键；`MessageSender.send`（`support/MessageSender.java:40-66`）以 `MessageBuilder.copyHeaders(outboundHeaders(envelope))` 构建消息，无额外头入口——同实例跨命令事件经无键分区路由可落不同分区，消费侧乱序。
3. **消费侧终态守卫已由先行批覆盖**（复核成立）：`LeaveRequestUseCase.onTaskCompleted`（:171-179，仅 `APPLYING` 推进）、`onTaskRejected`（:182-190，`isTerminal` 幂等）、`onInstanceCompleted`（:197-217，终态幂等 + UNKNOWN 不落终态）——评审「INSTANCE_COMPLETED 先到压掉中间态」的回退面已被现守卫消除；本批补**乱序到达回归用例**锁死（终态已写→迟到 TASK_COMPLETED 不推进）。
4. **P1-11 运行面缺失（拆分决定性事实）**：leave=单体试点（决策②，`k8s` 无 business-leave Deployment、`business-leave` 无 application.yml——P1-09 专项范围）；prod `acme.stream.enabled=false`（bootstrap :94）→ DLQ 消费/落库/告警/重放**无任何运行时表面**。分布式 leaveEventIn binding/DLQ topic 属 P1-09 上栈专项交付面（届时补 business-leave application.yml + 网关路由 + 本批 DLQ 设施一并）。**bootstrap :33/:38/:94 三处关闭键为语义误导**：`spring.cloud.stream.enabled` 非 SCSt 有效开关（SCSt 4.x 绑定初始化由函数定义+条件化函数 bean 决定）——单体惰性关闭的真实机制=消费函数 bean 条件化缺席（`LeaveWorkflowEventConsumer` `@ConditionalOnProperty acme.stream.enabled`）+ `function.definition: ""`；零 Docker 全量测试（无 Kafka 环境启动全部 54 模块）即关闭态不连 Kafka 的实证。
5. **MessageSender 头构建单点**：`outboundHeaders(envelope)`（:69-74+）为协议头注入单点（BR-014）；分区键属传输面头，经扩展头重载注入（不改既有签名，向后兼容）。
6. **kafka binder 分区键机制**：`spring.cloud.stream.kafka.bindings.<binding>.producer.messageKeyExpression: headers['partitionKey']`（SCSt kafka binder producer 属性）——发布侧注入 `partitionKey` 头即可按 key 哈希同分区保序；框架不硬依赖 kafka 头常量（binder-agnostic 口径保持，头名写入 workflow yml 绑定配置单源）。
7. **测试底座**：workflow-service 既有 `WorkflowEventPublishIntegrationTest`（2 例，AFTER_COMMIT 发布链）与 `WorkflowEventEnvelopeContractTest`（wire 形态）；framework-stream 既有测试域（RedisDedupStore/IdempotentGuard 等）；E2E（`LeaveE2eIntegrationTest`，stream 显式开启+嵌入式 KRaft Kafka）为分区键端到端面。

## 设计定案

1. **afterCommit 直派（P2-12①）**：`EventPublishInterceptor.afterCommit` 删除 `CompletableFuture.runAsync` 包装，直接 `eventPublisher.publish(event)`——publish 为廉价提交（Kafka 实现内部专属单线程 executor 异步发送、Logging 实现同步 no-op），提交序=事务提交序；commonPool 残留消除。
2. **分区键（P2-12②）**：`MessageSender` 增扩展头重载 `send(binding, envelope, extraHeaders)`（既有双签名不动）；`KafkaWorkflowEventPublisher.doSend` 注入 `partitionKey = "instance-" + instanceId`（实例级事件与节点级事件同实例同键；instanceId 空不注入）——workflow yml 绑定 producer `messageKeyExpression: headers['partitionKey']` 按键哈希同分区，同实例跨命令事件全局保序。
3. **消费乱序回归锁（P2-12③）**：business-leave 单测新增「INSTANCE_COMPLETED 先写 REJECTED 终态 → 迟到 TASK_COMPLETED 不推进不落库」（守卫既有，用例锁死防回潮）。
4. **bootstrap 开关语义修正（P1-11a）**：三处键注释改为真实机制声明（`spring.cloud.stream.enabled` 为文档性键非有效开关；真实关闭=条件化函数 bean 缺席 + definition 空；开启=E2E/测试域显式覆盖），消除误导。
5. **P1-11 拆分说明**：DLQ 消费落库+告警通道+重放任务与分布式 leaveEventIn/DLQ binding **拆分至 P1-09 上分布式栈专项**（运行面不存在——leave 单体试点 + prod stream 关闭下实现即死代码，违反诚实交付）；双形态 binding 下发面文档化进 ENV-STACK 双形态节。拆分经方案 §4 P1-11 行正文标注（跟踪入口仍为方案，非本契约）。

## 验收标准

- AC-1 given 事务提交后事件收集非空 when afterCommit 执行 then `WorkflowEventPublishIntegrationTest` 既有 2 例全绿（发布仍 AFTER_COMMIT 触发、失败不反噬），无 commonPool 异步包装（代码审阅面：interceptor 无 CompletableFuture）。
- AC-2 given WorkflowEvent 非 null 且 instanceId 非空 when publisher.doSend 构建信封经 MessageSender 发送 then send 调用携带扩展头 `partitionKey=instance-<id>`；instanceId 空事件不注入头仍可发布 → {用例名}；instanceId 空 → {用例名}
- AC-3 given 额外头 Map 非空 when send(binding, envelope, extraHeaders) 构建出站消息 then 额外头合并进消息头且协议头（MESSAGE_ID/IDEMPOTENT_KEY/TRACE_ID）与 CONTENT_TYPE=application/json 不丢失 → {用例名}
- AC-4 given workflow yml 绑定 producer 配置 messageKeyExpression=headers['partitionKey'] when E2E 全链发布消费 then 配置就位且 E2E（嵌入式 Kafka 全链）2 例仍绿（发布→消费端到端在分区键形态下不回归） → {用例名}
- AC-5 given INSTANCE_COMPLETED 先写 REJECTED 终态 when 迟到 TASK_COMPLETED 到达 then INSTANCE_COMPLETED 先写 REJECTED → TASK_COMPLETED 迟到 → 状态保持 REJECTED、零 save；TASK_COMPLETED 先 APPROVING → INSTANCE_COMPLETED 后到 → 正常收敛（既有守卫回归锚）。
- AC-6 given 本批文档面改动落地 when 审阅 then bootstrap 三处键注释为真实机制声明（enabled 非有效开关/条件化 bean 缺席+definition 空）；ENV-STACK 双形态节补 binding 下发面文档；方案 §4 P1-11 行拆分登记 → {文档审阅，无用例}
- AC-7 given 三模块改动 when 模块级 mvn test then `framework-stream`、`platform-workflow-service`、`business-leave` 模块 `mvn test` 全绿 0 跳过（判定口径 `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`，Maven 完整路径 `"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd"`、`-pl` 模块相对路径）；全量 mvn 交 CI 如实登记；无统一 lint 脚本禁表述为 lint 通过。

## 变更清单

| # | 文件 | 动作 | 说明 |
|---|---|---|---|
| 1 | `acme-framework/framework-stream/.../support/MessageSender.java` | M | 增 `send(binding, envelope, extraHeaders)` 重载（头合并单点内收） |
| 2 | `acme-platform/platform-workflow/.../kernel/KafkaWorkflowEventPublisher.java` | M | doSend 注入 partitionKey 头（instanceId 空不注入）；javadoc 补分区键口径 |
| 3 | `acme-platform/platform-workflow/.../kernel/interceptor/EventPublishInterceptor.java` | M | 删 runAsync 包装改直派；javadoc 更新 |
| 4 | `acme-platform/platform-workflow/platform-workflow-service/src/main/resources/application.yml` | M | 绑定 producer `messageKeyExpression: headers['partitionKey']` |
| 5 | `acme-business/business-leave/src/test/java/.../application/REQ20261128183425EventOrderingGuardTest.java` | A | AC-5 乱序回归锁（2 例） |
| 6 | `acme-framework/framework-stream/src/test/java/.../REQ20261128183425MessageSenderHeadersTest.java` | A | AC-3 扩展头单测 |
| 7 | `acme-platform/platform-workflow/platform-workflow-service/src/test/java/.../REQ20261128183425PublisherPartitionKeyTest.java` | A | AC-2 分区键头单测 |
| 8 | `acme-bootstrap/src/main/resources/application.yml` | M | P1-11a 三处开关注释语义修正 |
| 9 | `workspace/env-stack/ENV-STACK.md` | M | 双形态节 binding 下发面文档（AC-6） |
| 10 | `docs/code-review-remediation-plan-2026-11-27.md` | M | §4 P1-11 行拆分登记（AC-6） |

## 边界与并行避让

- 禁改 `WorkflowEventEnvelope`（wire 载荷零变更）；禁改 `business-leave` main 代码（B4-事务 已合流，本批仅其测试目录新增用例）；禁改 `.ai/`、`.ai-memory/`、`contracts/`（本契约由主线程维护）。
- 并行批次占用：B5（framework-notify/web/mobile 面）未开工；无同文件交叠。
- Flyway 无迁移；错误码零新增（B3b 已合流全码面，勿触碰）。


## 流程状态（TL 每次行动后更新；这是唯一现场，跨会话据此恢复）

- phase: DONE             # 2026-11-28 主线程实施+收口：三模块门禁全绿（framework-stream 57/workflow-service 89/business-leave 54，含 8 新用例），rebase 合并（主线）+E2E 2/2 合并后复验绿（分区键端到端）。过程中修复：重载歧义 null 转型、保留头集合实现（测试按契约抓到覆盖缺陷）、Mockito timeout 异步等待、两处 import。P1-11 拆分已录方案 §4
- 确认记录：[2026-11-28] 本契约为 B4-消息 收敛实现面（P2-12 全项 + P1-11 可达面）；P1-11 DLQ 运行面拆分至 P1-09 上栈专项（决定性事实=leave 单体试点 + prod stream 关闭下无运行表面，拆分登记方案 §4）。
