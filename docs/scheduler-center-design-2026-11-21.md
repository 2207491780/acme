# ACME 独立定时任务调度中心设计规格（platform-scheduler）

> 定稿日期：2026-11-21 ｜ 任务锚点：规划文档 WS-JOB（JOB-0 交付物）
> 上游决策：ADR `2026-11-21-02-bpm-job-self-build.md`（纯自研路线）；本文档 = 调度内核/指令通道/执行器 SDK/联动协议的终稿事实源。
> 后续契约（JOB-1~5）以本文档为规格基线；实现偏差须回改本文档并留 ADR 记录。

## 1. 服务布局

| 项 | 定稿 |
|---|---|
| 模块 | `acme-platform/platform-scheduler/`：`platform-scheduler-api`（DTO + Feign 契约）＋ `platform-scheduler-service` |
| 端口 | 9884（workflow 9883 之后） |
| 网关路由 | `/admin/v1/scheduler/**` → `lb://platform-scheduler`；springdoc 分组 `/v3/api-docs/scheduler` |
| 迁移空间 | `db/migration/scheduler/` 自 V1 起 |
| 部署 | k8s base + kustomization + deploy.sh `ALL_SERVICES` + seed-nacos.sh；bootstrap 单体可选聚合（bootstrap 形态下调度中心内嵌运行，执行器=本地直调） |

## 2. 控制面持久化模型（4 表）

| 表 | 关键列 | 索引 |
|---|---|---|
| sch_job_definition | job_key(uk), app_name, handler, trigger_type(CRON/FIXED_RATE/ONCE), cron_expr, fixed_rate_ms, once_fire_at, param(jsonb), retry_policy(jsonb: maxAttempts/initial_interval_ms/backoff EXPONENTIAL+jitter), shard_total, timeout_ms, misfire_policy(FIRE_ONCE_NOW/IGNORE), status(ENABLED/DISABLED), tenant_id(可空=平台任务), remark | uk(job_key)；idx(app_name,status) |
| sch_executor_registry | app_name, instance_id, host, port, handlers(jsonb 数组), heartbeat_at, status(ONLINE/OFFLINE) | uk(app_name,instance_id)；idx(heartbeat_at) |
| sch_job_execution | definition_id, job_key, trigger_type(SCHEDULE/MANUAL/RETRY/EVENT), fire_time, state(PENDING/DISPATCHED/RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELLED), shard_index(可空), attempt, result(jsonb 摘要), error, cost_ms, trace_id | idx(job_key,fire_time)；idx(state)；idx(fire_time) |
| sch_scheduler_lock | name(uk), lock_until, locked_at, locked_by | ——（ShedLock JDBC 口径，框架既有） |

- 审计列对齐 `sys_*` 惯例；job 归属租户（可空）为后续租户级任务配额预留（TENANT-4）。

## 3. 调度内核

### 3.1 触发模型：DB 恢复 + 内存时间轮执行

- **正确性事实源 = PG**；**执行精度 = 内存时间轮**：
  - 启动恢复：扫描 `sch_job_definition(status=ENABLED)` 计算 next_fire_time，加载窗口内（now ~ now+5min）任务进轮；执行历史不恢复（历史是结果记录）。
  - 主循环 tick=1s：从时间轮取到期任务 → 生成 `sch_job_execution(PENDING)` → 指令下发（§4）→ 成功 DISPATCHED；同 tick 扫 DB 兜底（`next_fire <= now` 的漏网任务，防时间轮/内存漂移）。
  - 时间轮实现：秒级 wheel（60 槽）+ 分钟 wheel（60 槽）分层降级，纯 JDK 并发结构自研（~300 行，不引依赖）；FIXED_RATE 触发后即计算下一轮入轮。
- **misfire**（启动恢复/停机期间错过的触发）：FIRE_ONCE_NOW（默认，补跑一次）/ IGNORE（跳到下次）按 definition 配置。
- **cron 解析**：Spring `CronExpression`（6 位域），零新依赖。

### 3.2 多实例安全（无单点、不重触发）

- 选主：ShedLock（framework 既有）——主循环单发锁 `lockAtMostFor=5s / lockAtLeastFor=1s`（JDBC 口径，锁表见 §2；Redis 模式可在配置切换）。持有锁的实例跑 tick；非持有实例仅提供 API/兜底。
- 语义：同一 job_key 同一 fire_time 至多一条 execution（uk(def_id, fire_time, trigger) 保证 SCHEDULE 触发幂等；MANUAL/RETRY/EVENT 不受此约束）。
- 水平扩展收益：执行下发是 IO 密集（HTTP 调用），执行线程池独立于 tick 线程，下发吞吐随实例数横向增长（tick 单发只影响触发扫描，不影响下发并发）。

## 4. 指令通道（ADR 终稿：HTTP 直推为主，Kafka 为辅）

- **主通道 HTTP 直推**：scheduler → Nacos `lb://{app_name}` → `POST /internal/v1/job/executions/{executionId}/run`（body：jobKey/handler/param/shardContext/fireTime/traceId/attempt）。
  - 响应语义：HTTP 200 + `{accepted:true}` = 执行器已接单（状态→RUNNING）；**结果异步上报**：执行完成后执行器回调 `POST /internal/v1/scheduler/executions/{executionId}/result`（state=SUCCESS/FAILED + result/error/cost_ms）。
  - 下载/长任务不阻塞调度线程；接单超时（默认 10s 未 200）→ 视为下发失败走重试。
  - 失败重试：下发失败或执行 FAILED → 按 retry_policy 重新生成 execution（trigger_type=RETRY, attempt+1）；耗尽 → FAILED 终态 + 告警。
- **辅通道 Kafka**（framework-stream）：仅用于 ① 事件触发任务（JOB-5：业务事件 topic → 触发 job）；② 执行日志/审计流（大文本 result 走流不走响应体）。选 HTTP 主通道理由：失败语义明确（连接失败/接单超时/业务失败三态分离）、取消可控（CANCELLED 只需不发）、精度不受 consumer lag 影响。
- **超时控制**：definition.timeout_ms；scheduler 独立巡检线程扫 `RUNNING 且 now-fire_time>timeout` → 标 TIMEOUT（执行器侧同时 interrupt）→ 走重试策略。
- **内部端点安全**：`/internal/**` 不进网关路由白名单；服务间调用带 `X-Internal-Token`（环境配置注入，对齐现有服务间 Feign 鉴权惯例）。

## 5. 执行器 SDK（framework-job 升级，双模式）

- **managed 模式**（新，默认对齐调度中心）：
  - `@JobHandler("jobKey")` 方法注册：启动收集 → `POST /internal/v1/scheduler/executors/register`（appName/instanceId/host:port/handlers[]）→ 心跳 10s（失败指数退避重试，不影响业务）。
  - 指令消费：暴露 `POST /internal/v1/job/executions/{id}/run`：校验 token → 提交业务线程池（隔离组，队列上限可配，满时拒绝接单 accepted=false）→ 执行 handler(param, shardContext) → 回调 result。
  - 分片：shard_total>1 时 scheduler 按 executor 实例轮询分配 shard_index，SDK 透传 shardContext{index,total} 给 handler。
  - 中断：TIMEOUT 巡检触发时执行器对当前线程 interrupt + handler 自行响应中断（SDK 提供 cancel 检查点工具）。
- **standalone 模式**（兼容存量）：`@AcmeTask` + `@Scheduled` 现行为不变（ShedLock 互斥保留）；`acme.job.managed=false` 显式启用。**存量任务迁移路径**（JOB-3）：定义进调度中心（cron 照抄），代码注解去掉 @Scheduled 保留 @JobHandler（迁移语义：调度权从进程内转到中心，互斥仍由 SDK 执行池+job 定义串行策略保证）。
- 同 job 串行语义：definition 默认禁止并发（上一 execution 未终态不触发下一次；SCHEDULE tick 到期检查），`acme:concurrent` 显式允许时可并行。

## 6. 告警与运维面

- 终态 FAILED/TIMEOUT → 通知中心站内信（复用 120102 管道）+ 平台运维权限点可见；webhook 渠道二期。
- 管理页（JOB-3）：任务 CRUD/启停/手动触发（MANUAL execution）/执行历史（状态/耗时/结果/错误/traceId）/执行器监控（在线实例/心跳/handler 清单）。

## 7. 与 BPM 联动协议（对齐 bpm-engine-design §6）

- 注册：`POST /internal/v1/scheduler/jobs`（trigger=ONCE@due_at, callback={service,path}）——workflow 服务以内部 token 调用；注销 `DELETE /internal/v1/scheduler/jobs/{job_key}`。
- 回调 fire：scheduler HTTP 直推 workflow 内部端点；幂等由 workflow 侧状态 CAS 保证（spec §6）。
- 定位：BPM 是 scheduler 的第一个平台内客户；联动协议即 §4 通用协议，无 BPM 专用通道。

## 8. 测试策略（JOB-1 起执行）

- 内核：cron 解析边界（夏令时/月末/星期）、时间轮降级、misfire 两策略、uk 幂等（同 fire_time 重复扫描不重复触发）、选主切换（杀持有者 5s 内接管不重复触发）。
- 通道：接单超时重试、执行 FAILED 重试链、重试耗尽告警、TIMEOUT 巡检+中断、接单拒绝（执行池满）语义。
- SDK：注册/心跳恢复、分片上下文透传、串行互斥（上一未终态不触发）、standalone 双模式隔离。
- 集成：Testcontainers PG + Kafka（对齐现有集成测试口径）；模拟两个服务实例（Testcontainers 或 spring 随机端口双上下文）验证不重触发。

## 9. 依赖登记汇总

- 后端：零新增（时间轮/分层轮自研；ShedLock/framework-lock/CronExpression/framework-stream 均已有）。
- 前端：零新增（管理页复用 CrudTable 既有惯例）。
