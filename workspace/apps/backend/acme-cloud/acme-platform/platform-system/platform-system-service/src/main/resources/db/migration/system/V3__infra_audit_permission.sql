-- platform-infra 审计查询权限点增补种子（platform-infra-audit 特性；幂等口径同 V2：
-- 固定主键 + ON CONFLICT (id) DO NOTHING；V2 基线后权限点 ID 自 32 起、菜单 ID 自 8 起续编）。

-- 1. 审计查询权限点（infra 域，权限目录平台级统一登记）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (32, 'infra:audit:list', '审计事件查询', 'API', 'GET', '/admin/v1/infra/audit-events',
        'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 平台运营目录 + 审计查询菜单（permissionCode 关联权限点控制可见性）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort_order, visible,
                      permission_code, status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (8, 0, '平台运营', 'DIRECTORY', '/infra', NULL, 'monitor', 2, true, NULL, 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (9, 8, '审计查询', 'MENU', '/infra/audit-events', 'infra/audit/index', 'log', 1, true, 'infra:audit:list', 'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 3. 超管角色绑定（权限点 32；菜单 8/9；绑定行 ID 自 32 起续编）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (32, 'default', 1, 32, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (8,  'default', 1, 8, now(), 'system', now(), 'system', 0, false),
    (9,  'default', 1, 9, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
