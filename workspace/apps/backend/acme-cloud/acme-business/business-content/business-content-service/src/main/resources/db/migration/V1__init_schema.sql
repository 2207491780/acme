-- business-content 初始 schema（架构设计 3.2～3.5 / 12.3.3：Flyway 版本化脚本与代码同仓，实体映射唯一来源）
-- 通用列由 framework-jpa AuditBase 映射：id bigint 主键（雪花赋值，12.3.1 禁自增，无序列/自增）、
-- created_at/updated_at timestamptz、created_by/updated_by varchar(64)、version bigint 乐观锁、deleted boolean。
-- 软删行过滤由 AuditBase @SQLRestriction 统一保障（BR-015）；本期无 tenant_id（内容域单租户形态）。

CREATE TABLE biz_content (
    id            bigint PRIMARY KEY,
    title         varchar(200) NOT NULL,
    category_code varchar(32)  NOT NULL,
    author        varchar(64)  NOT NULL,
    status        varchar(16)  NOT NULL,
    views         bigint       NOT NULL,
    excerpt       varchar(500) NOT NULL,
    content       text         NOT NULL,
    cover         varchar(500),
    created_at    timestamptz  NOT NULL,
    created_by    varchar(64)  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    updated_by    varchar(64)  NOT NULL,
    version       bigint       NOT NULL,
    deleted       boolean      NOT NULL
);
-- 索引清单（架构设计 3.5）
CREATE INDEX idx_biz_content_status ON biz_content (status);
CREATE INDEX idx_biz_content_category ON biz_content (category_code);
CREATE INDEX idx_biz_content_author ON biz_content (author);
CREATE INDEX idx_biz_content_updated_at ON biz_content (updated_at);

CREATE TABLE biz_content_category (
    id         bigint PRIMARY KEY,
    code       varchar(32) NOT NULL,
    name       varchar(64) NOT NULL,
    sort_order integer     NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(64) NOT NULL,
    version    bigint      NOT NULL,
    deleted    boolean     NOT NULL,
    CONSTRAINT uk_biz_content_category_code UNIQUE (code)
);

CREATE TABLE biz_hot_search_word (
    id         bigint PRIMARY KEY,
    keyword    varchar(64) NOT NULL,
    sort_order integer     NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(64) NOT NULL,
    version    bigint      NOT NULL,
    deleted    boolean     NOT NULL,
    CONSTRAINT uk_biz_hot_search_keyword UNIQUE (keyword)
);
