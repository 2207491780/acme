-- 请假审批试点（REQ-20261122-233425 确认⑤；业务域模块迁移空间自 V1——版本号跨服务各自协调，
-- 契约 frontmatter migrations 预分配 business-leave=V1）。schema 独占 business_leave（12.1.1；
-- 单体经 MonolithFlywayConfig 独立 Flyway 实例承载）。审计列对齐 sys_* 惯例（雪花主键禁自增
-- 12.3.1 / 审计填充由框架 AuditFillListener 承担 / version 乐观锁 / deleted 软删列对齐 AuditBase）。
-- 租户隔离：leave_request @TenantId（tenant_id）。

-- 请假单表。status 语义域（确认⑤终稿）：APPLYING（已建单待启动）→ APPROVING（流程实例已启动
-- 审批中）→ FILED（HR 备案 ServiceTask 落库）→ DONE / REJECTED（INSTANCE_COMPLETED 事件回写终态）。
CREATE TABLE leave_request (
    id          bigint       PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    user_id     varchar(64)  NOT NULL,
    user_name   varchar(64)  NOT NULL,
    leave_type  varchar(16)  NOT NULL,
    reason      varchar(512),
    start_time  timestamptz  NOT NULL,
    end_time    timestamptz  NOT NULL,
    status      varchar(16)  NOT NULL,
    instance_id bigint,
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL
);
CREATE INDEX idx_leave_request_tenant_status ON leave_request (tenant_id, status);
CREATE INDEX idx_leave_request_instance ON leave_request (instance_id);
CREATE INDEX idx_leave_request_business_key ON leave_request (id);
