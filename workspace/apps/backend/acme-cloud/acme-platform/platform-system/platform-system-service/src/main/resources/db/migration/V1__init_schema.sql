-- platform-system 初始 schema（架构设计 7.2 / 12.3.3：Flyway 版本化脚本与代码同仓，实体映射唯一来源）
-- 通用列由 framework-jpa AuditBase 映射：id bigint 主键（雪花赋值，12.3.1 禁自增）、
-- created_at/updated_at timestamptz、created_by/updated_by varchar(64)、version bigint 乐观锁、deleted boolean。
-- 租户归属表 tenant_id varchar(64)（@TenantId 自动过滤 12.4.1）；平台目录表（sys_tenant/sys_permission/sys_menu）
-- 无 tenant_id（Q-D04）；绑定表物理删除（deleted 恒 false，7.2.7）。

CREATE TABLE sys_tenant (
    id          bigint PRIMARY KEY,
    tenant_code varchar(64)  NOT NULL,
    tenant_name varchar(128) NOT NULL,
    status      varchar(16)  NOT NULL,
    remark      varchar(255),
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL,
    CONSTRAINT uk_sys_tenant_code UNIQUE (tenant_code)
);

CREATE TABLE sys_user (
    id          bigint PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    username    varchar(64)  NOT NULL,
    nickname    varchar(64)  NOT NULL,
    email       varchar(128),
    phone       varchar(32),
    status      varchar(16)  NOT NULL,
    built_in    boolean      NOT NULL,
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);
CREATE INDEX idx_sys_user_tenant ON sys_user (tenant_id);

CREATE TABLE sys_role (
    id          bigint PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    role_code   varchar(64)  NOT NULL,
    role_name   varchar(64)  NOT NULL,
    status      varchar(16)  NOT NULL,
    remark      varchar(255),
    built_in    boolean      NOT NULL,
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL,
    CONSTRAINT uk_sys_role_tenant_code UNIQUE (tenant_id, role_code)
);

CREATE TABLE sys_permission (
    id               bigint PRIMARY KEY,
    permission_code  varchar(128) NOT NULL,
    permission_name  varchar(64)  NOT NULL,
    permission_type  varchar(16)  NOT NULL,
    http_method      varchar(16),
    http_path        varchar(255),
    status           varchar(16)  NOT NULL,
    created_at       timestamptz  NOT NULL,
    created_by       varchar(64)  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    updated_by       varchar(64)  NOT NULL,
    version          bigint       NOT NULL,
    deleted          boolean      NOT NULL,
    CONSTRAINT uk_sys_permission_code UNIQUE (permission_code)
);

CREATE TABLE sys_menu (
    id              bigint PRIMARY KEY,
    parent_id       bigint       NOT NULL,
    menu_name       varchar(64)  NOT NULL,
    menu_type       varchar(16)  NOT NULL,
    path            varchar(255),
    component       varchar(255),
    icon            varchar(64),
    sort_order      integer      NOT NULL,
    visible         boolean      NOT NULL,
    permission_code varchar(128),
    status          varchar(16)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    created_by      varchar(64)  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    updated_by      varchar(64)  NOT NULL,
    version         bigint       NOT NULL,
    deleted         boolean      NOT NULL
);
CREATE INDEX idx_sys_menu_parent ON sys_menu (parent_id);

CREATE TABLE sys_dict_type (
    id         bigint PRIMARY KEY,
    tenant_id  varchar(64)  NOT NULL,
    dict_code  varchar(64)  NOT NULL,
    dict_name  varchar(64)  NOT NULL,
    status     varchar(16)  NOT NULL,
    remark     varchar(255),
    created_at timestamptz  NOT NULL,
    created_by varchar(64)  NOT NULL,
    updated_at timestamptz  NOT NULL,
    updated_by varchar(64)  NOT NULL,
    version    bigint       NOT NULL,
    deleted    boolean      NOT NULL,
    CONSTRAINT uk_sys_dict_type_tenant_code UNIQUE (tenant_id, dict_code)
);

CREATE TABLE sys_dict_item (
    id          bigint PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    dict_type_id bigint      NOT NULL,
    item_label  varchar(128) NOT NULL,
    item_value  varchar(128) NOT NULL,
    sort_order  integer      NOT NULL,
    status      varchar(16)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL,
    CONSTRAINT uk_sys_dict_item_type_value UNIQUE (tenant_id, dict_type_id, item_value)
);
CREATE INDEX idx_sys_dict_item_type ON sys_dict_item (dict_type_id);

CREATE TABLE sys_user_role (
    id         bigint PRIMARY KEY,
    tenant_id  varchar(64) NOT NULL,
    user_id    bigint      NOT NULL,
    role_id    bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(64) NOT NULL,
    version    bigint      NOT NULL,
    deleted    boolean     NOT NULL,
    CONSTRAINT uk_sys_user_role UNIQUE (tenant_id, user_id, role_id)
);

CREATE TABLE sys_role_permission (
    id            bigint PRIMARY KEY,
    tenant_id     varchar(64) NOT NULL,
    role_id       bigint      NOT NULL,
    permission_id bigint      NOT NULL,
    created_at    timestamptz NOT NULL,
    created_by    varchar(64) NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    varchar(64) NOT NULL,
    version       bigint      NOT NULL,
    deleted       boolean     NOT NULL,
    CONSTRAINT uk_sys_role_permission UNIQUE (tenant_id, role_id, permission_id)
);

CREATE TABLE sys_role_menu (
    id         bigint PRIMARY KEY,
    tenant_id  varchar(64) NOT NULL,
    role_id    bigint      NOT NULL,
    menu_id    bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(64) NOT NULL,
    version    bigint      NOT NULL,
    deleted    boolean     NOT NULL,
    CONSTRAINT uk_sys_role_menu UNIQUE (tenant_id, role_id, menu_id)
);
