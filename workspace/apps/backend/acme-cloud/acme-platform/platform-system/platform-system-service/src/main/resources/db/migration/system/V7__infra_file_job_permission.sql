-- 文件服务 + 任务调度 + Excel demo 权限点增补种子（REQ-20261114-071607；幂等口径同 V2/V3/V5：
-- 固定主键 + ON CONFLICT (id) DO NOTHING；权限点 ID 自 39 起（V2 用 1-31、V3 用 32、V4 用 33、
-- V5 用 34-38）、绑定行 ID 自 43 起续编（V5 用至 42）。权限目录平台级统一登记；
-- 超管角色（role_id=1）延续「内置超管绑定全量权限点」口径补绑。
-- 契约裁决⑦：demo 三端点（demo-template/demo-export/demo-import）由单点 infra:excel:demo 覆盖，
-- 目录行以 excel/** 通配路径登记（方法列登记改动面最大的 demo-import POST；
-- 服务侧 RequirePermission 对三端点统一校验该权限点，AC-5）。

-- 1. 权限点（API 类型，http_method + http_path 接口级；元数据读取 GET /{id} 归 list 读语义）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (39, 'infra:file:list',     '文件查询',       'API', 'GET',    '/admin/v1/infra/files',              'ENABLED', now(), 'system', now(), 'system', 0, false),
    (40, 'infra:file:upload',   '文件上传',       'API', 'POST',   '/admin/v1/infra/files',              'ENABLED', now(), 'system', now(), 'system', 0, false),
    (41, 'infra:file:download', '文件下载',       'API', 'GET',    '/admin/v1/infra/files/{id}/content', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (42, 'infra:file:delete',   '文件删除',       'API', 'DELETE', '/admin/v1/infra/files/{id}',         'ENABLED', now(), 'system', now(), 'system', 0, false),
    (43, 'infra:job:list',      '任务注册表查询', 'API', 'GET',    '/admin/v1/infra/jobs',               'ENABLED', now(), 'system', now(), 'system', 0, false),
    (44, 'infra:job:run',       '任务手动触发',   'API', 'POST',   '/admin/v1/infra/jobs/{jobKey}/run',  'ENABLED', now(), 'system', now(), 'system', 0, false),
    (45, 'infra:excel:demo',    'Excel 范式演示', 'API', 'POST',   '/admin/v1/infra/excel/**',           'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色补绑新权限点（绑定行 ID 自 43 起续编）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (43, 'default', 1, 39, now(), 'system', now(), 'system', 0, false),
    (44, 'default', 1, 40, now(), 'system', now(), 'system', 0, false),
    (45, 'default', 1, 41, now(), 'system', now(), 'system', 0, false),
    (46, 'default', 1, 42, now(), 'system', now(), 'system', 0, false),
    (47, 'default', 1, 43, now(), 'system', now(), 'system', 0, false),
    (48, 'default', 1, 44, now(), 'system', now(), 'system', 0, false),
    (49, 'default', 1, 45, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
