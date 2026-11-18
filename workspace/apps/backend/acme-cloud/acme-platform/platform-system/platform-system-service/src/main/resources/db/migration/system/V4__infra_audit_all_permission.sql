-- 全租户审计视图权限点增补种子（consumer-auth-refresh-dataperm 数据权限落地）：
-- 权限点 ID 自 33 起、绑定行 ID 自 33 起续编（V3 后）；幂等口径同 V2（固定主键 + ON CONFLICT DO NOTHING）。

-- 1. 全租户审计视图权限点（数据权限：具备=全量视图，仅具备 infra:audit:list=本人视图）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (33, 'infra:audit:list:all', '全租户审计视图', 'API', 'GET', '/admin/v1/infra/audit-events',
        'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色绑定
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (33, 'default', 1, 33, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
