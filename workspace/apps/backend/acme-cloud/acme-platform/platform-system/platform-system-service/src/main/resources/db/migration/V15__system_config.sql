-- 租户级系统配置（REQ-20261116-230354）：sys_config 每租户一行 + system:config:* 权限点
-- + 系统管理目录下「系统配置」菜单 + 内置超管角色补绑。
-- 通用列由 framework-flex AuditBase 映射：id bigint 主键（雪花赋值，禁自增）、
-- created_at/updated_at timestamptz、created_by/updated_by varchar(64)、version bigint 乐观锁、
-- deleted boolean 软删；租户归属列 tenant_id varchar(64)（@TenantId 自动过滤 12.4.1）。
-- 幂等口径同 V2/V3/V10：固定主键 + ON CONFLICT (id) DO NOTHING；
-- 权限点 ID 自 66 起续编（V10 用至 65）、绑定行 ID 自 70 起续编（V10 用至 69）、
-- 菜单 ID 自 10 起续编（V3 用至 9）、role_menu 绑定行 ID 自 10 起续编（V3 用至 9）。
-- GET 认证即可读（全员强制生效需读取，无权限标注）；PUT 经 system:config:update
-- （RequirePermissionInterceptor 强制）；system:config:list 供菜单显隐（me.menus fail-closed）。
-- 租户管理员开通授权（TenantInitUseCase）按非保留域全量授予，system:config:* 自动纳入，无需逐租户种子。

-- 1. 系统配置（每租户一行：tenant_id 唯一；缺省值=水印开 / 闲置锁屏关，无行租户按缺省生效）
CREATE TABLE sys_config (
    id                   bigint PRIMARY KEY,
    tenant_id            varchar(64) NOT NULL,
    watermark_enabled    boolean     NOT NULL DEFAULT true,
    lock_idle_timeout_ms integer     NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    created_by           varchar(64) NOT NULL,
    updated_at           timestamptz NOT NULL,
    updated_by           varchar(64) NOT NULL,
    version              bigint      NOT NULL,
    deleted              boolean     NOT NULL,
    CONSTRAINT uk_sys_config_tenant UNIQUE (tenant_id)
);

-- 2. 权限点（API 类型，http_method + http_path 接口级）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (66, 'system:config:list',   '系统配置查询', 'API', 'GET', '/admin/v1/system/config', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (67, 'system:config:update', '系统配置修改', 'API', 'PUT', '/admin/v1/system/config', 'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 3. 系统管理目录下「系统配置」菜单（排序居现有菜单之后；icon 取 web 图标注册表标识，V14 口径）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort_order, visible,
                      permission_code, status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (10, 1, '系统配置', 'MENU', '/system/config', 'system/config/index', 'settings', 7, true, 'system:config:list', 'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 4. 超管角色补绑新权限点（绑定行 ID 自 70 起续编）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (70, 'default', 1, 66, now(), 'system', now(), 'system', 0, false),
    (71, 'default', 1, 67, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 5. 超管角色补绑新菜单（role_menu 绑定行 ID 自 10 起续编）
INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (10, 'default', 1, 10, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;