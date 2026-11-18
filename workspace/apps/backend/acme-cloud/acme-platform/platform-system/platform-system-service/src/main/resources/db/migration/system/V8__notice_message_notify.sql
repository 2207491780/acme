-- 通知公告 + 站内信（REQ-20261114-120102 消息通知闭环一期）；通用列由 AuditBase 映射：
-- id bigint 主键（雪花赋值，禁自增）、created_at/updated_at timestamptz、created_by/updated_by varchar(64)、
-- version bigint 乐观锁、deleted boolean 软删；租户归属表 tenant_id varchar(64)（@TenantId 自动过滤 12.4.1）。
-- 权限点种子幂等口径同 V2/V3/V5/V7：固定主键 + ON CONFLICT (id) DO NOTHING；
-- 权限点 ID 自 46 起（V2 用 1-31、V3 用 32、V4 用 33、V5 用 34-38、V7 用 39-45）、
-- 绑定行 ID 自 50 起续编（V5 用至 42、V7 用 43-49）。权限目录平台级统一登记；
-- 超管角色（role_id=1）延续「内置超管绑定全量权限点」口径补绑；me.menus 语义不动（无新增菜单）。

-- 1. 通知公告（title/content 富文本 HTML；status: DRAFT/PUBLISHED；publish_at 发布时间，草稿为空）
CREATE TABLE sys_notice (
    id          bigint PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    title       varchar(128) NOT NULL,
    content     text         NOT NULL,
    status      varchar(16)  NOT NULL,
    publish_at  timestamptz,
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL
);
CREATE INDEX idx_sys_notice_tenant ON sys_notice (tenant_id);

-- 2. 站内信（发布扇出/单发的收件语义实体；is_read/read_at 收件状态，未读=false/空）
CREATE TABLE sys_message (
    id           bigint PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL,
    sender_id    bigint       NOT NULL,
    recipient_id bigint       NOT NULL,
    title        varchar(128) NOT NULL,
    content      text         NOT NULL,
    is_read      boolean      NOT NULL,
    read_at      timestamptz,
    created_at   timestamptz  NOT NULL,
    created_by   varchar(64)  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    updated_by   varchar(64)  NOT NULL,
    version      bigint       NOT NULL,
    deleted      boolean      NOT NULL
);
CREATE INDEX idx_sys_message_recipient ON sys_message (tenant_id, recipient_id, is_read);

-- 3. 权限点（API 类型，http_method + http_path 接口级；详情 GET /{id}、未读数 GET /unread-count
--    归 list 读语义，照 V7「元数据读取归 list 读语义」先例；read-all 与 {id}/read 共用 message:read）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (46, 'system:notice:list',    '公告列表',   'API', 'GET',    '/admin/v1/system/notices',              'ENABLED', now(), 'system', now(), 'system', 0, false),
    (47, 'system:notice:create',  '创建公告',   'API', 'POST',   '/admin/v1/system/notices',              'ENABLED', now(), 'system', now(), 'system', 0, false),
    (48, 'system:notice:update',  '编辑公告',   'API', 'PUT',    '/admin/v1/system/notices/{noticeId}',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (49, 'system:notice:delete',  '删除公告',   'API', 'DELETE', '/admin/v1/system/notices/{noticeId}',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (50, 'system:notice:publish', '发布公告',   'API', 'PUT',    '/admin/v1/system/notices/{noticeId}/publish', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (51, 'system:message:send',   '发送站内信', 'API', 'POST',   '/admin/v1/system/messages',             'ENABLED', now(), 'system', now(), 'system', 0, false),
    (52, 'system:message:list',   '站内信收件箱', 'API', 'GET',  '/admin/v1/system/messages**',           'ENABLED', now(), 'system', now(), 'system', 0, false),
    (53, 'system:message:read',   '站内信标记已读', 'API', 'PUT', '/admin/v1/system/messages/**',          'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 4. 超管角色补绑新权限点（绑定行 ID 自 50 起续编）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (50, 'default', 1, 46, now(), 'system', now(), 'system', 0, false),
    (51, 'default', 1, 47, now(), 'system', now(), 'system', 0, false),
    (52, 'default', 1, 48, now(), 'system', now(), 'system', 0, false),
    (53, 'default', 1, 49, now(), 'system', now(), 'system', 0, false),
    (54, 'default', 1, 50, now(), 'system', now(), 'system', 0, false),
    (55, 'default', 1, 51, now(), 'system', now(), 'system', 0, false),
    (56, 'default', 1, 52, now(), 'system', now(), 'system', 0, false),
    (57, 'default', 1, 53, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
