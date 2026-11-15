-- 组织架构与数据权限（REQ-20261114-120104）：部门/岗位/角色数据范围 + 用户部门岗位归属。
-- 通用列由 framework-flex AuditBase 映射：id bigint 主键（雪花赋值，禁自增）、created_at/updated_at timestamptz、
-- created_by/updated_by varchar(64)、version bigint 乐观锁、deleted boolean 软删；
-- 租户归属表 tenant_id varchar(64)（@TenantId 自动过滤 12.4.1）；绑定表物理删除（deleted 恒 false，7.2.7）。
-- 权限点种子幂等口径同 V2/V3/V5/V7/V8：固定主键 + ON CONFLICT (id) DO NOTHING；
-- 权限点 ID 自 54 起续编（V2 用 1-31、V3 用 32、V4 用 33、V5 用 34-38、V7 用 39-45、V8 用 46-53）、
-- 绑定行 ID 自 58 起续编（V5 用至 42、V7 用 43-49、V8 用 50-57）。权限目录平台级统一登记；
-- 超管角色（role_id=1）延续「内置超管绑定全量权限点」口径补绑；me.menus 语义不动（无新增菜单）。

-- 1. 部门（parent_id 根哨兵 0；path 物化路径 "/1/3/" 尾斜杠形态——子树解析按前缀 LIKE，
--    尾斜杠杜绝 "/10/" 误匹配 "/100/" 前缀；重挂父时子树前缀整体重写）
CREATE TABLE sys_dept (
    id          bigint PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    parent_id   bigint       NOT NULL DEFAULT 0,
    path        varchar(512) NOT NULL,
    dept_name   varchar(64)  NOT NULL,
    sort_order  integer      NOT NULL DEFAULT 0,
    status      varchar(16)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL
);
CREATE INDEX idx_sys_dept_tenant ON sys_dept (tenant_id);
CREATE INDEX idx_sys_dept_parent ON sys_dept (parent_id);
CREATE INDEX idx_sys_dept_path ON sys_dept (path);

-- 2. 岗位（(tenant_id, position_code) 租户内唯一；契约仅定义 CRUD 与引用删除守卫，
--    编码唯一对齐字典类型 (tenant_id, dict_code) 先例）
CREATE TABLE sys_position (
    id            bigint PRIMARY KEY,
    tenant_id     varchar(64)  NOT NULL,
    position_code varchar(64)  NOT NULL,
    position_name varchar(64)  NOT NULL,
    status        varchar(16)  NOT NULL,
    created_at    timestamptz  NOT NULL,
    created_by    varchar(64)  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    updated_by    varchar(64)  NOT NULL,
    version       bigint       NOT NULL,
    deleted       boolean      NOT NULL,
    CONSTRAINT uk_sys_position_tenant_code UNIQUE (tenant_id, position_code)
);
CREATE INDEX idx_sys_position_tenant ON sys_position (tenant_id);

-- 3. 角色数据范围列（五档 ALL/DEPT/DEPT_AND_CHILD/SELF/CUSTOM；可空——null/未知取值经
--    framework-security DataScope.parse 归缺省档 ALL，存量角色语义不变：
--    原无数据权限过滤面，ALL 档维持全量可见；业务代码写入恒为档位枚举名）
ALTER TABLE sys_role ADD COLUMN data_scope varchar(16);

-- 4. 角色-自定义部门集（CUSTOM 档数据来源；绑定表物理删除，scope 非 CUSTOM 时全量重写清空）
CREATE TABLE sys_role_dept (
    id         bigint PRIMARY KEY,
    tenant_id  varchar(64) NOT NULL,
    role_id    bigint      NOT NULL,
    dept_id    bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(64) NOT NULL,
    version    bigint      NOT NULL,
    deleted    boolean     NOT NULL,
    CONSTRAINT uk_sys_role_dept UNIQUE (tenant_id, role_id, dept_id)
);
CREATE INDEX idx_sys_role_dept_role ON sys_role_dept (tenant_id, role_id);

-- 5. 用户部门/岗位归属（可空：存量用户不归属部门/岗位，语义不变；索引服务列表部门过滤）
ALTER TABLE sys_user ADD COLUMN dept_id bigint;
ALTER TABLE sys_user ADD COLUMN position_id bigint;
CREATE INDEX idx_sys_user_dept ON sys_user (dept_id);
CREATE INDEX idx_sys_user_position ON sys_user (position_id);

-- 6. 权限点（API 类型，http_method + http_path 接口级；部门树 GET 归 list 读语义，V7 先例）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (54, 'system:dept:list',      '部门列表',   'API', 'GET',    '/admin/v1/system/depts',                    'ENABLED', now(), 'system', now(), 'system', 0, false),
    (55, 'system:dept:create',    '创建部门',   'API', 'POST',   '/admin/v1/system/depts',                    'ENABLED', now(), 'system', now(), 'system', 0, false),
    (56, 'system:dept:update',    '修改部门',   'API', 'PUT',    '/admin/v1/system/depts/{deptId}',           'ENABLED', now(), 'system', now(), 'system', 0, false),
    (57, 'system:dept:delete',    '删除部门',   'API', 'DELETE', '/admin/v1/system/depts/{deptId}',           'ENABLED', now(), 'system', now(), 'system', 0, false),
    (58, 'system:position:list',  '岗位列表',   'API', 'GET',    '/admin/v1/system/positions',                'ENABLED', now(), 'system', now(), 'system', 0, false),
    (59, 'system:position:create','创建岗位',   'API', 'POST',   '/admin/v1/system/positions',                'ENABLED', now(), 'system', now(), 'system', 0, false),
    (60, 'system:position:update','修改岗位',   'API', 'PUT',    '/admin/v1/system/positions/{positionId}',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (61, 'system:position:delete','删除岗位',   'API', 'DELETE', '/admin/v1/system/positions/{positionId}',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (62, 'system:role:scope',     '设置角色数据范围', 'API', 'PUT', '/admin/v1/system/roles/{roleId}/data-scope', 'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 7. 超管角色补绑新权限点（绑定行 ID 自 58 起续编）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (58, 'default', 1, 54, now(), 'system', now(), 'system', 0, false),
    (59, 'default', 1, 55, now(), 'system', now(), 'system', 0, false),
    (60, 'default', 1, 56, now(), 'system', now(), 'system', 0, false),
    (61, 'default', 1, 57, now(), 'system', now(), 'system', 0, false),
    (62, 'default', 1, 58, now(), 'system', now(), 'system', 0, false),
    (63, 'default', 1, 59, now(), 'system', now(), 'system', 0, false),
    (64, 'default', 1, 60, now(), 'system', now(), 'system', 0, false),
    (65, 'default', 1, 61, now(), 'system', now(), 'system', 0, false),
    (66, 'default', 1, 62, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
