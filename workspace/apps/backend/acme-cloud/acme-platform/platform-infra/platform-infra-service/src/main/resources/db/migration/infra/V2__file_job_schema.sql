-- platform-infra 文件服务 + 任务调度 schema（REQ-20261114-071607；12.3.3 Flyway 版本化脚本与代码同仓，
-- 实体映射唯一来源）。列集 = 契约 §变更清单 + 审计基类口径（framework-flex AuditBase：
-- 雪花主键 / created_at / created_by / updated_at / updated_by / version / deleted）。
-- sys_file 软删（deleted 逻辑删除列，FR-009）+ 租户归属（tenant_id @TenantId 自动过滤 12.4.1）；
-- sys_job_execution 为平台级执行记录（无租户列，调度不承载租户语义）；trigger 列名取 trigger_type
-- （trigger 为 PG 保留字不可作列名，语义不变：MANUAL/SCHEDULE）。
-- shedlock_locks：ShedLock JDBC 锁表（仅当 V1 未含——V1 无锁表，此处建立；列宽同
-- ddl-templates/shedlock_locks.sql 模板，表名按契约 §变更清单）。

CREATE TABLE sys_file (
    id            bigint PRIMARY KEY,
    bucket        varchar(64)  NOT NULL,
    object_key    varchar(512) NOT NULL,
    original_name varchar(255) NOT NULL,
    size          bigint       NOT NULL,
    content_type  varchar(128),
    biz_type      varchar(64),
    biz_id        varchar(64),
    tenant_id     varchar(64)  NOT NULL,
    uploaded_by   varchar(64),
    created_at    timestamptz  NOT NULL,
    created_by    varchar(64)  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    updated_by    varchar(64)  NOT NULL,
    version       bigint       NOT NULL,
    deleted       boolean      NOT NULL
);
CREATE INDEX idx_sys_file_tenant_created ON sys_file (tenant_id, created_at);
CREATE INDEX idx_sys_file_biz ON sys_file (biz_type, biz_id);

CREATE TABLE sys_job_execution (
    id           bigint PRIMARY KEY,
    job_key      varchar(128) NOT NULL,
    status       varchar(16)  NOT NULL,
    started_at   timestamptz  NOT NULL,
    finished_at  timestamptz,
    error        text,
    trigger_type varchar(16)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    created_by   varchar(64)  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    updated_by   varchar(64)  NOT NULL,
    version      bigint       NOT NULL,
    deleted      boolean      NOT NULL
);
CREATE INDEX idx_sys_job_execution_job ON sys_job_execution (job_key, started_at);

CREATE TABLE shedlock_locks (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
