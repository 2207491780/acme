-- 登录安全域权限点种子（REQ-20261114-120105；幂等口径同 V2/V3/V5/V7/V8/V9：
-- 固定主键 + ON CONFLICT (id) DO NOTHING；权限点 ID 自 63 起续编（V2 用 1-31、V3 用 32、
-- V4 用 33、V5 用 34-38、V7 用 39-45、V8 用 46-53、V9 用 54-62）、
-- 绑定行 ID 自 67 起续编（V9 用至 66）。权限目录平台级统一登记；
-- 超管角色（role_id=1）延续「内置超管绑定全量权限点」口径补绑。

-- 1. 权限点（API 类型，http_method + http_path 接口级）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (63, 'auth:loginlog:list',  '登录日志查询', 'API', 'GET',    '/admin/v1/auth/login-logs',           'ENABLED', now(), 'system', now(), 'system', 0, false),
    (64, 'auth:online:list',    '在线用户查询', 'API', 'GET',    '/admin/v1/auth/online-users',         'ENABLED', now(), 'system', now(), 'system', 0, false),
    (65, 'auth:online:revoke',  '在线用户踢下线', 'API', 'DELETE', '/admin/v1/auth/online-users/{sessionId}', 'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色补绑新权限点（绑定行 ID 自 67 起续编）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (67, 'default', 1, 63, now(), 'system', now(), 'system', 0, false),
    (68, 'default', 1, 64, now(), 'system', now(), 'system', 0, false),
    (69, 'default', 1, 65, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
