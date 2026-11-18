-- platform-infra 初始 schema（拆分评审结论 / 12.3.3：Flyway 版本化脚本与代码同仓，实体映射唯一来源）
-- 审计表列集 = p5 设计 18.2 定稿模板 + event_id 唯一键（M-12 消费幂等的 DB 兜底，双保险）。
-- 通用列由 framework-jpa AuditBase 映射；审计行不可变（deleted 恒 false，无软删语义）。

CREATE TABLE acme_op_audit (
    id          bigint PRIMARY KEY,
    event_id    varchar(64)  NOT NULL,
    trace_id    varchar(64),
    span_id     varchar(64),
    tenant_id   varchar(64)  NOT NULL,
    operator_id varchar(64),
    channel     varchar(8),
    action      varchar(128) NOT NULL,
    target_type varchar(64),
    target_id   varchar(64),
    content     text,
    occurred_at timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL,
    CONSTRAINT uk_infra_audit_event UNIQUE (event_id)
);
CREATE INDEX idx_infra_audit_tenant_time ON acme_op_audit (tenant_id, occurred_at);
CREATE INDEX idx_infra_audit_trace ON acme_op_audit (trace_id);
