-- 流程启动补偿任务表（REQ-20261128-092210 确认②；business-leave 厂商独立系列 V2——TL 预分配，
-- 禁自行取号）。apply 事务内与 leave_request 同事务原子落库补偿任务行（PENDING, attempts=0），
-- 流程启动移出事务（afterCommit/重试任务补投）——重试管道与单据状态机隔离（认领/回写不碰
-- leave 行乐观锁版本，避免与事件消费者状态推进相互 STALE_WRITE 噪声）。
-- 幂等口径照 V30/V31 先例（全部 IF NOT EXISTS，Flyway 只前向）。审计列对齐 leave_request V1
-- （雪花主键禁自增 12.3.1 / 审计填充由框架 AuditFillListener 承担 / version 乐观锁 / deleted
-- 软删列对齐 AuditBase）。租户隔离：leave_start_task @TenantId（tenant_id）。

CREATE TABLE IF NOT EXISTS leave_start_task (
    id            bigint       PRIMARY KEY,
    tenant_id     varchar(64)  NOT NULL,
    leave_id      bigint       NOT NULL,
    status        varchar(16)  NOT NULL,
    attempts      int          NOT NULL,
    next_retry_at timestamptz,
    last_error    varchar(1024),
    created_at    timestamptz  NOT NULL,
    created_by    varchar(64)  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    updated_by    varchar(64)  NOT NULL,
    version       bigint       NOT NULL,
    deleted       boolean      NOT NULL
);

-- 一单一任务（认领竞态之外的第一防线）与扫描索引（PENDING 到期腿）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_leave_start_task_leave ON leave_start_task (leave_id);
CREATE INDEX IF NOT EXISTS idx_leave_start_task_scan ON leave_start_task (status, next_retry_at);

-- leave_request(instance_id) 部分唯一索引（双启动最后兜底，防任务认领竞态之外的错绑；
-- 存量单实例 pilot 无重复数据风险）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_leave_request_instance_uid ON leave_request (instance_id)
    WHERE instance_id IS NOT NULL;
