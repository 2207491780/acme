-- 仓储切片测试隔离环境（16.1 等效隔离环境）：H2 内存库；列定义对齐 V1__init_schema.sql（user/role/user_role）
CREATE TABLE sys_user (
    id         BIGINT PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL,
    username   VARCHAR(64)  NOT NULL,
    nickname   VARCHAR(64)  NOT NULL,
    email      VARCHAR(128),
    phone      VARCHAR(32),
    status     VARCHAR(16)  NOT NULL,
    built_in   BOOLEAN      NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    created_by VARCHAR(64)  NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    updated_by VARCHAR(64)  NOT NULL,
    version    BIGINT       NOT NULL,
    deleted    BOOLEAN      NOT NULL,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE sys_role (
    id         BIGINT PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL,
    role_code  VARCHAR(64)  NOT NULL,
    role_name  VARCHAR(64)  NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    remark     VARCHAR(255),
    built_in   BOOLEAN      NOT NULL,
    parent_id  BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMP    NOT NULL,
    created_by VARCHAR(64)  NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    updated_by VARCHAR(64)  NOT NULL,
    version    BIGINT       NOT NULL,
    deleted    BOOLEAN      NOT NULL,
    CONSTRAINT uk_sys_role_tenant_code UNIQUE (tenant_id, role_code)
);

CREATE TABLE sys_user_role (
    id         BIGINT PRIMARY KEY,
    tenant_id  VARCHAR(64) NOT NULL,
    user_id    BIGINT      NOT NULL,
    role_id    BIGINT      NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP   NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    version    BIGINT      NOT NULL,
    deleted    BOOLEAN     NOT NULL,
    CONSTRAINT uk_sys_user_role UNIQUE (tenant_id, user_id, role_id)
);
