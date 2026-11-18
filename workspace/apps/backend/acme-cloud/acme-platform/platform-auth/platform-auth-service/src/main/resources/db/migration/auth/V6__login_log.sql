-- 登录安全域（REQ-20261114-120105）：登录日志表（仅插入不改——业务语义无更新端点；
-- deleted/version 等通用列由 framework-flex AuditBase 映射口径统一，供保留期清理任务
-- （platform-infra login-log-retention-cleanup）按「软删超期物理清理」范式执行）。
-- 平台级表：不含 tenant_id（登录链路无租户上下文，Q-D04 同型）；
-- username 冗余记录（登录时输入原值），trace_id 取请求链路 TraceContext。

CREATE TABLE sys_login_log (
    id          bigint PRIMARY KEY,
    username    varchar(64)  NOT NULL,
    user_type   varchar(8)   NOT NULL,
    ip          varchar(64),
    user_agent  varchar(512),
    result      varchar(16)  NOT NULL,
    fail_reason varchar(64),
    trace_id    varchar(32),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  varchar(64)  NOT NULL DEFAULT 'system',
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  varchar(64)  NOT NULL DEFAULT 'system',
    version     bigint       NOT NULL DEFAULT 0,
    deleted     boolean      NOT NULL DEFAULT false
);
CREATE INDEX idx_sys_login_log_username ON sys_login_log (username);
CREATE INDEX idx_sys_login_log_created_at ON sys_login_log (created_at);
CREATE INDEX idx_sys_login_log_deleted_updated ON sys_login_log (deleted, updated_at);
