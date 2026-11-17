-- platform-infra 运行异常日志 schema（REQ-20261117-061017 日志中心完善；12.3.3 Flyway 版本化脚本与代码同仓，
-- 实体映射唯一来源，幂等口径注释对齐 V2：列集 = 契约 §变更清单 + 审计基类口径（framework-flex AuditBase：
-- 雪花主键 / created_at / created_by / updated_at / updated_by / version / deleted）。
-- sys_op_exception 软删列保留（deleted 逻辑删除列，保留期清理任务按「软删超期物理清理」范式执行）+
-- 租户归属（tenant_id @TenantId 自动过滤 12.4.1；无租户语境的系统级事件经收集侧归一保留值 system）。
-- DDL 经 Flyway 版本化单次执行（同 V2 口径，不使用 IF NOT EXISTS）。

CREATE TABLE sys_op_exception (
    id             bigint PRIMARY KEY,
    service        varchar(32)   NOT NULL,
    trace_id       varchar(64),
    span_id        varchar(64),
    tenant_id      varchar(64)   NOT NULL,
    operator_id    varchar(64),
    method         varchar(8),
    path           varchar(256),
    status_code    int           NOT NULL,
    exception_type varchar(256)  NOT NULL,
    message        varchar(1024),
    stack_summary  text,
    occurred_at    timestamptz   NOT NULL,
    created_at     timestamptz   NOT NULL,
    created_by     varchar(64)   NOT NULL,
    updated_at     timestamptz   NOT NULL,
    updated_by     varchar(64)   NOT NULL,
    version        bigint        NOT NULL,
    deleted        boolean       NOT NULL
);
CREATE INDEX idx_sys_op_exception_tenant_time ON sys_op_exception (tenant_id, occurred_at);
CREATE INDEX idx_sys_op_exception_trace ON sys_op_exception (trace_id);
CREATE INDEX idx_sys_op_exception_service_time ON sys_op_exception (service, occurred_at);
