-- platform-auth 初始 schema（架构设计 7.3 / 12.3.3：Flyway 版本化脚本与代码同仓）
-- 通用列由 framework-jpa AuditBase 映射（同 platform-system V1 说明）。
-- Q-D04：两表含 tenant_id 归属标注列但不标 @TenantId（按 user_id / tokenId 全局唯一定位，登录链路无租户上下文）。

CREATE TABLE auth_credential (
    id                  bigint PRIMARY KEY,
    user_id             bigint       NOT NULL,
    tenant_id           varchar(64)  NOT NULL,
    password_hash       varchar(100) NOT NULL,
    password_updated_at timestamptz  NOT NULL,
    created_at          timestamptz  NOT NULL,
    created_by          varchar(64)  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    updated_by          varchar(64)  NOT NULL,
    version             bigint       NOT NULL,
    deleted             boolean      NOT NULL,
    CONSTRAINT uk_auth_credential_user UNIQUE (user_id)
);

CREATE TABLE auth_session (
    id         bigint PRIMARY KEY,
    token_id   varchar(64) NOT NULL,
    user_id    bigint      NOT NULL,
    tenant_id  varchar(64) NOT NULL,
    channel    varchar(8)  NOT NULL,
    status     varchar(16) NOT NULL,
    issued_at  timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(64) NOT NULL,
    version    bigint      NOT NULL,
    deleted    boolean     NOT NULL,
    CONSTRAINT uk_auth_session_token UNIQUE (token_id)
);
CREATE INDEX idx_auth_session_user_status ON auth_session (user_id, status);
