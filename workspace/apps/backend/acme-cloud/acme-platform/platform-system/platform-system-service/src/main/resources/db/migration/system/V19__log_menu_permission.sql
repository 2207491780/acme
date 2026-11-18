-- 日志中心 + 定时任务管理权限点/菜单种子（REQ-20261117-061017；幂等口径同 V15：
-- 固定主键 + ON CONFLICT (id) DO NOTHING；权限点 ID 自 68 起续编（V15 用至 67）、
-- 绑定行 ID 自 72 起续编（V15 用至 71）、菜单 ID 自 11 起续编（V15 用至 10）、
-- role_menu 绑定行 ID 自 11 起续编（V15 用至 10）。权限目录平台级统一登记；
-- 超管角色（role_id=1）延续「内置超管绑定全量权限点」口径补绑。
-- 异常日志（infra:exception:list 为新权限点 68）；定时任务菜单挂既有权限点 infra:job:list（V7 权限点 43，
-- 勿重复插入）；两菜单挂「平台运营」目录（id=8，monitor 图标，V3 种子）下，sort 顺延（V3 用至 1）；
-- path/component 对齐 web 既有路由与页面（确认⑧⑨）；icon 取 web 图标注册表标识（V14 口径，
-- bug/schedule 均在注册表内，且对齐 web NAV 两个新路由的展示图标）。

-- 1. 权限点（API 类型，http_method + http_path 接口级）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (68, 'infra:exception:list', '异常日志查询', 'API', 'GET', '/admin/v1/infra/op-exceptions',
     'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色补绑新权限点（绑定行 ID 自 72 起续编）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (72, 'default', 1, 68, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 3. 平台运营目录下「异常日志 / 定时任务」菜单（permissionCode 关联权限点控制可见性）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort_order, visible,
                      permission_code, status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (11, 8, '异常日志', 'MENU', '/op-exceptions', 'infra/exceptions/index', 'bug', 2, true,
     'infra:exception:list', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (12, 8, '定时任务', 'MENU', '/jobs', 'infra/jobs/index', 'schedule', 3, true,
     'infra:job:list', 'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 4. 超管角色补绑新菜单（role_menu 绑定行 ID 自 11 起续编）
INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (11, 'default', 1, 11, now(), 'system', now(), 'system', 0, false),
    (12, 'default', 1, 12, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
