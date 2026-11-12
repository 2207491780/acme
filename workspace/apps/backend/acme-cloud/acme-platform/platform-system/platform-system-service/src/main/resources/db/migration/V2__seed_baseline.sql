-- platform-system 基线种子（架构设计 5.4 / FR-009、Q-005 裁定 B：版本化迁移脚本 + 幂等）
-- 幂等口径（AC-009）：固定主键（小数值 ID 与雪花大数值无碰撞）+ ON CONFLICT (id) DO NOTHING；
-- 重复执行（repair / 重建）不产生重复账号。种子仅在迁移脚本，不硬编码于业务代码（8.2.1）。
-- 默认租户编码 'default' 为租户标识取值来源（sys_user.tenant_id 等租户归属列存该标识）。

-- 1. 默认租户
INSERT INTO sys_tenant (id, tenant_code, tenant_name, status, remark,
                        created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (1, 'default', '默认租户', 'ENABLED', '平台内置默认租户',
        now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 内置超管角色（built_in 保护；种子绑定全量权限点，与拦截器短路等价双保险，架构设计 5.2）
INSERT INTO sys_role (id, tenant_id, role_code, role_name, status, remark, built_in,
                      created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (1, 'default', 'super_admin', '内置超管', 'ENABLED', '平台内置超级管理员', true,
        now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 3. 超管用户（归属默认租户；密码经 platform-auth V2 种子凭据设置，本表不含任何凭据形态）
INSERT INTO sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                      created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (1, 'default', 'admin', '超级管理员', NULL, NULL, 'ENABLED', true,
        now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 4. 用户-角色绑定
INSERT INTO sys_user_role (id, tenant_id, user_id, role_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (1, 'default', 1, 1, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 5. 本批全部权限点目录（编码与 platform-system-api SystemPermissions 常量一一对应）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (1,  'auth:credential:manage', '凭据管理（设置/重置密码）', 'API', 'PUT',  '/admin/v1/auth/credentials/users/{userId}/password', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (2,  'system:user:list',       '用户列表',   'API', 'GET',    '/admin/v1/system/users',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (3,  'system:user:create',     '创建用户',   'API', 'POST',   '/admin/v1/system/users',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (4,  'system:user:query',      '用户详情',   'API', 'GET',    '/admin/v1/system/users/{userId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (5,  'system:user:update',     '修改用户',   'API', 'PUT',    '/admin/v1/system/users/{userId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (6,  'system:user:delete',     '删除用户',   'API', 'DELETE', '/admin/v1/system/users/{userId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (7,  'system:user:assign-role','用户角色绑定', 'API', 'PUT',  '/admin/v1/system/users/{userId}/roles', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (8,  'system:role:list',       '角色列表',   'API', 'GET',    '/admin/v1/system/roles',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (9,  'system:role:create',     '创建角色',   'API', 'POST',   '/admin/v1/system/roles',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (10, 'system:role:query',      '角色详情',   'API', 'GET',    '/admin/v1/system/roles/{roleId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (11, 'system:role:update',     '修改角色',   'API', 'PUT',    '/admin/v1/system/roles/{roleId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (12, 'system:role:delete',     '删除角色',   'API', 'DELETE', '/admin/v1/system/roles/{roleId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (13, 'system:role:grant',      '角色授权',   'API', 'PUT',    '/admin/v1/system/roles/{roleId}/permissions', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (14, 'system:permission:list', '权限点列表', 'API', 'GET',    '/admin/v1/system/permissions', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (15, 'system:permission:create','创建权限点', 'API', 'POST',  '/admin/v1/system/permissions', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (16, 'system:permission:query','权限点详情', 'API', 'GET',    '/admin/v1/system/permissions/{permissionId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (17, 'system:permission:update','修改权限点', 'API', 'PUT',   '/admin/v1/system/permissions/{permissionId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (18, 'system:permission:delete','删除权限点', 'API', 'DELETE','/admin/v1/system/permissions/{permissionId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (19, 'system:menu:list',       '菜单树',     'API', 'GET',    '/admin/v1/system/menus',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (20, 'system:menu:create',     '创建菜单',   'API', 'POST',   '/admin/v1/system/menus',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (21, 'system:menu:query',      '菜单详情',   'API', 'GET',    '/admin/v1/system/menus/{menuId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (22, 'system:menu:update',     '修改菜单',   'API', 'PUT',    '/admin/v1/system/menus/{menuId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (23, 'system:menu:delete',     '删除菜单',   'API', 'DELETE', '/admin/v1/system/menus/{menuId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (24, 'system:dict:list',       '字典查询',   'API', 'GET',    '/admin/v1/system/dict-types/**', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (25, 'system:dict:create',     '创建字典',   'API', 'POST',   '/admin/v1/system/dict-types/**', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (26, 'system:dict:update',     '修改字典',   'API', 'PUT',    '/admin/v1/system/dict-*/**',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (27, 'system:dict:delete',     '删除字典',   'API', 'DELETE', '/admin/v1/system/dict-*/**',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (28, 'system:tenant:list',     '租户列表',   'API', 'GET',    '/admin/v1/system/tenants',     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (29, 'system:tenant:create',   '创建租户',   'API', 'POST',   '/admin/v1/system/tenants',     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (30, 'system:tenant:query',    '租户详情',   'API', 'GET',    '/admin/v1/system/tenants/{tenantId}', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (31, 'system:tenant:update',   '修改租户',   'API', 'PUT',    '/admin/v1/system/tenants/**',  'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 6. 最小菜单基线（系统管理目录 + 六项菜单，permissionCode 关联列表权限点控制可见性）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort_order, visible,
                      permission_code, status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (1, 0, '系统管理', 'DIRECTORY', '/system',  NULL,          'setting', 1, true, NULL,                  'ENABLED', now(), 'system', now(), 'system', 0, false),
    (2, 1, '用户管理', 'MENU',      '/system/users',       'system/user/index',    'user',     1, true, 'system:user:list',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (3, 1, '角色管理', 'MENU',      '/system/roles',       'system/role/index',    'peoples',  2, true, 'system:role:list',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (4, 1, '权限点管理', 'MENU',    '/system/permissions', 'system/permission/index', 'key',   3, true, 'system:permission:list', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (5, 1, '菜单管理', 'MENU',      '/system/menus',       'system/menu/index',    'tree',     4, true, 'system:menu:list',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (6, 1, '字典管理', 'MENU',      '/system/dicts',       'system/dict/index',    'dict',     5, true, 'system:dict:list',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (7, 1, '租户管理', 'MENU',      '/system/tenants',     'system/tenant/index',  'tenant',   6, true, 'system:tenant:list',     'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 7. 超管角色全量权限点绑定（31 项）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
SELECT g.id, 'default', 1, g.permission_id, now(), 'system', now(), 'system', 0, false
FROM (SELECT generate_series(1, 31) AS id, generate_series(1, 31) AS permission_id) g
ON CONFLICT (id) DO NOTHING;

-- 8. 超管角色全量菜单绑定（7 项）
INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
SELECT g.id, 'default', 1, g.menu_id, now(), 'system', now(), 'system', 0, false
FROM (SELECT generate_series(1, 7) AS id, generate_series(1, 7) AS menu_id) g
ON CONFLICT (id) DO NOTHING;
