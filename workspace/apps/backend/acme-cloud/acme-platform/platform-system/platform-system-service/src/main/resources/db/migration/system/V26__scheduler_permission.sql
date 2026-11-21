-- 调度中心权限点种子（REQ-20261121-071002 确认②；幂等口径同 V24/V25：固定主键 + ON CONFLICT (id) DO
-- NOTHING）。无菜单节点（确认②：菜单归 JOB-3），仅 API 权限点 + 超管绑定。
-- 水位：sys_permission 自 73 起续编（V24 用至 72）、sys_role_permission 绑定行自 77 起续编（V24 用至 76）；
-- 超管角色（role_id=1，default 租户）延续「内置超管绑定全量权限点」口径补绑。
-- 权限点语义（契约 frontmatter 终稿）：
--   73 scheduler:job:list       GET  /admin/v1/scheduler/jobs（executors 列表同归本点）
--   74 scheduler:job:create     POST /admin/v1/scheduler/jobs
--   75 scheduler:job:update     PUT  /admin/v1/scheduler/jobs/{id} 与 /{id}/status 与 /{id}/trigger
--                               （目录行以 PUT /admin/v1/scheduler/jobs/** 通配登记，V7 裁决⑦先例；
--                                服务侧 RequirePermission 三端点统一校验该权限点）
--   76 scheduler:job:delete     DELETE /admin/v1/scheduler/jobs/{id}
--   77 scheduler:execution:list GET  /admin/v1/scheduler/executions（executors 列表归 73 job:list）

-- 1. 权限点（API 类型，http_method + http_path 接口级）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (73, 'scheduler:job:list',       '调度任务查询', 'API', 'GET',    '/admin/v1/scheduler/jobs',
     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (74, 'scheduler:job:create',     '调度任务创建', 'API', 'POST',   '/admin/v1/scheduler/jobs',
     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (75, 'scheduler:job:update',     '调度任务更新', 'API', 'PUT',    '/admin/v1/scheduler/jobs/**',
     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (76, 'scheduler:job:delete',     '调度任务删除', 'API', 'DELETE', '/admin/v1/scheduler/jobs/{id}',
     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (77, 'scheduler:execution:list', '调度执行查询', 'API', 'GET',    '/admin/v1/scheduler/executions',
     'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色补绑新权限点（绑定行 ID 自 77 起续编；default 租户超管 role_id=1）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (77, 'default', 1, 73, now(), 'system', now(), 'system', 0, false),
    (78, 'default', 1, 74, now(), 'system', now(), 'system', 0, false),
    (79, 'default', 1, 75, now(), 'system', now(), 'system', 0, false),
    (80, 'default', 1, 76, now(), 'system', now(), 'system', 0, false),
    (81, 'default', 1, 77, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
