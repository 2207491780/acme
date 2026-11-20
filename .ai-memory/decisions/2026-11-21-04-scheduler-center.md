# 调度中心架构定稿：HTTP 直推通道 + 时间轮内核 + 执行器 SDK 双模式（JOB-0 预研交付）

日期：2026-11-21
关联：规格书 `docs/scheduler-center-design-2026-11-21.md`（终稿事实源）、ADR `2026-11-21-02-bpm-job-self-build.md`

## 背景

纯自研调度中心需在 JOB-1 动工前钉死：触发模型与精度保证、多实例不重触发方案、指令通道选型、存量 framework-job 兼容路径、与 BPM 定时事件联动协议。

## 决定

- **持久化模型**：4 表（definition/executor_registry/execution/scheduler_lock）；PG 为正确性事实源。
- **触发内核**：内存分层时间轮（秒+分钟，自研 ~300 行，tick=1s）+ 启动恢复扫描 + 每 tick DB 兜底扫描；misfire 默认 FIRE_ONCE_NOW；cron 用 Spring CronExpression。
- **多实例**：ShedLock 主循环单发锁（JDBC 口径，Redis 可切）；同 job 同 fire_time 唯一约束幂等；下发 IO 线程池独立于 tick，吞吐横向扩展。
- **指令通道**：HTTP 直推为主（接单 ack + 结果异步上报三态分离：下发失败/接单超时/执行失败），重试链 attempt+1、耗尽告警；Kafka 仅作事件触发（JOB-5）与日志流辅助；超时巡检+执行器中断。
- **执行器 SDK**：framework-job 升级双模式——managed（@JobHandler 注册+心跳+内部指令端点+分片上下文）/ standalone（@AcmeTask+@Scheduled 现行为不变）；迁移路径在 JOB-3；同 job 默认串行。
- **联动**：BPM 以内部 token 调 ONCE job 注册/注销 + HTTP 回调，协议即通用协议无专用通道。

## 被否的选项

- **Kafka 作主通道**：at-least-once + 手动 ack 使重试/超时/取消语义复杂化，精度受 consumer lag 影响，运行中任务不可取消；仅保留辅助场景。
- **Quartz 内嵌复用**：模型固化（trigger/jobstore 耦合）、集群方案基于 DB 悲观锁精度差、与自研管理面冲突。
- **纯 DB 轮询触发（XXL-Job 式）**：精度受限于轮询间隔与 DB 压力，秒级精度下扫描成本高；时间轮+DB 兜底兼顾精度与正确性。

## 影响面

- 后端/前端均零新依赖。
- JOB-1~5 契约以 `docs/scheduler-center-design-2026-11-21.md` 为规格基线；偏差须回改规格书并在此 ADR 追加记录。
