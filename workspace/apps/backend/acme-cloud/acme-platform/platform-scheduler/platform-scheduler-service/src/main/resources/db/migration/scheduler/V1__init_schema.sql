-- 调度中心控制面四表（REQ-20261121-071002 确认③终稿；docs/scheduler-center-design-2026-11-21 §2）。
-- 迁移空间 db/migration/scheduler 自 V1（版本号跨服务各自协调）；schema 独占 platform_scheduler（12.1.1）。
-- 审计列对齐 sys_* 惯例（雪花主键禁自增 12.3.1 / created_by 等由框架 AuditFillListener 填充）；
-- 软删除（deleted）仅任务定义承载业务语义（执行历史/注册表不删只查——列对齐 AuditBase 统一口径，
-- 恒 false）；jsonb 走文本形态（JsonbTypeHandler，platform-system 同款）。
-- 触发幂等：sch_job_execution 部分唯一索引 uk(def_id, fire_time) WHERE trigger_type='SCHEDULE'
-- AND attempt=0（确认⑤：同 job_key 同一 fire_time 至多一条 SCHEDULE execution；MANUAL/RETRY/EVENT
-- 不受此约束）。

-- 1. 任务定义表
CREATE TABLE sch_job_definition (
    id                bigint       PRIMARY KEY,
    job_key           varchar(128) NOT NULL,
    app_name          varchar(64)  NOT NULL,
    handler           varchar(128) NOT NULL,
    trigger_type      varchar(16)  NOT NULL,
    cron_expr         varchar(64),
    fixed_rate_ms     bigint,
    once_fire_at      timestamptz,
    param             jsonb,
    retry_policy      jsonb,
    shard_total       integer      NOT NULL DEFAULT 1,
    timeout_ms        bigint       NOT NULL DEFAULT 60000,
    misfire_policy    varchar(16)  NOT NULL,
    status            varchar(16)  NOT NULL,
    next_fire_time    timestamptz,
    tenant_id         varchar(64),
    remark            varchar(255),
    created_at        timestamptz  NOT NULL,
    created_by        varchar(64)  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    updated_by        varchar(64)  NOT NULL,
    version           bigint       NOT NULL,
    deleted           boolean      NOT NULL,
    CONSTRAINT uk_sch_job_definition_key UNIQUE (job_key)
);

-- 2. 执行器注册表
CREATE TABLE sch_executor_registry (
    id             bigint       PRIMARY KEY,
    app_name       varchar(64)  NOT NULL,
    instance_id    varchar(128) NOT NULL,
    host           varchar(64)  NOT NULL,
    port           integer,
    handlers       jsonb,
    heartbeat_at   timestamptz  NOT NULL,
    status         varchar(16)  NOT NULL,
    created_at     timestamptz  NOT NULL,
    created_by     varchar(64)  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    updated_by     varchar(64)  NOT NULL,
    version        bigint       NOT NULL,
    deleted        boolean      NOT NULL,
    CONSTRAINT uk_sch_executor_instance UNIQUE (app_name, instance_id)
);
CREATE INDEX idx_sch_executor_heartbeat ON sch_executor_registry (heartbeat_at);

-- 3. 执行历史表
CREATE TABLE sch_job_execution (
    id             bigint       PRIMARY KEY,
    definition_id  bigint       NOT NULL,
    job_key        varchar(128) NOT NULL,
    trigger_type   varchar(16)  NOT NULL,
    fire_time      timestamptz  NOT NULL,
    state          varchar(16)  NOT NULL,
    attempt        integer      NOT NULL DEFAULT 0,
    shard_index    integer,
    result         jsonb,
    error          varchar(512),
    cost_ms        bigint,
    trace_id       varchar(64),
    created_at     timestamptz  NOT NULL,
    created_by     varchar(64)  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    updated_by     varchar(64)  NOT NULL,
    version        bigint       NOT NULL,
    deleted        boolean      NOT NULL
);
-- SCHEDULE 触发幂等（部分唯一索引，确认③⑤终稿）：同定义同 fire_time 至多一条首发 execution
CREATE UNIQUE INDEX uk_sch_execution_schedule_fire
    ON sch_job_execution (definition_id, fire_time)
    WHERE trigger_type = 'SCHEDULE' AND attempt = 0;
CREATE INDEX idx_sch_execution_job_fire ON sch_job_execution (job_key, fire_time);
CREATE INDEX idx_sch_execution_state ON sch_job_execution (state);
CREATE INDEX idx_sch_execution_fire_time ON sch_job_execution (fire_time);

-- 4. 调度器选主锁表（ShedLock JDBC 口径，列定义对齐 ShedLock 5.16 官方模板 TIMESTAMP(无时区)；
--    JdbcTemplateLockProvider 显式指定表名 sch_scheduler_lock，列名 name/lock_until/locked_at/locked_by）。
--    ⚠️ 参照系纪律（REQ-20261121-071002 round-2 根因收口，round-2 实证含探针复现）：
--    usingDbTime 的获取/解锁 SQL 全部以 timezone('utc', CURRENT_TIMESTAMP)（UTC 墙钟，naive）写入与比较，
--    本表列必须为 TIMESTAMP(无时区)——naive 对 naive 无会话时区参与，参照系与会话时区无关；
--    若改 timestamptz，naive UTC 值会被 JDBC 会话时区（pgjdbc 启动包携带客户端默认时区，本地
--    Asia/Shanghai）重解释，ShedLock 自身写入即偏移 -8h，任何绝对时刻写入方（运维/测试裸 now()）
--    都会令锁行落在 ShedLock 比较视界 +8h → 假锁约 8 小时，tick 永久停摆（round-2 实测复现）。
--    因此所有外部写入方（运维/工具/测试）复位/占用锁行必须写 UTC 墙钟：lock_until =
--    timezone('utc', now()) ± interval，禁止裸 now()。
CREATE TABLE sch_scheduler_lock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
