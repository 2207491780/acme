-- BPM 工作流引擎权限点种子（REQ-20261121-142316 确认⑦；幂等口径同 V24~V26：固定主键 +
-- ON CONFLICT (id) DO NOTHING）。无菜单节点（确认⑦：菜单归 BPM-3），仅 API 权限点 + 超管绑定。
-- 水位：sys_permission 自 78 起续编（V26 用至 77）、sys_role_permission 绑定行自 82 起续编
-- （V26 用至 81）；超管角色（role_id=1，default 租户）延续「内置超管绑定全量权限点」口径补绑。
-- ⚠️ 本 REQ 加点后执行坑卡坑 16 全库水位扫描：历史测试 77/81 断言统一抬新值 82/86
-- （.ai-memory/playbooks/tester-pitfalls.md 坑 16）。
-- 权限点语义（契约确认⑦ frontmatter 终稿）：
--   78 workflow:definition:list     GET  /admin/v1/workflow/process-definitions（分页 + /{id} 详情）
--   79 workflow:definition:deploy   PUT  /admin/v1/workflow/process-definitions/{id}/xml
--                                   POST /admin/v1/workflow/process-definitions/{id}/deploy
--                                   POST /admin/v1/workflow/process-definitions/{id}/disable
--   80 workflow:instance:manage     POST /admin/v1/workflow/process-instances（启动）
--                                   POST /admin/v1/workflow/process-instances/{id}/terminate|suspend|resume
--                                   GET  /admin/v1/workflow/process-instances/{id}/history
--                                   GET  /admin/v1/workflow/process-instances/initiated（我发起）
--                                   GET  /admin/v1/workflow/process-instances（分页）
--   81 workflow:task:list           GET  /admin/v1/workflow/tasks/todo | /tasks/done
--   82 workflow:task:operate        POST /admin/v1/workflow/tasks/{taskId}/complete|reject|claim|unclaim|transfer|delegate

-- 1. 权限点（API 类型，http_method + http_path 接口级）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (78, 'workflow:definition:list',   '流程定义查询', 'API', 'GET',    '/admin/v1/workflow/process-definitions',
     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (79, 'workflow:definition:deploy', '流程定义部署', 'API', 'POST',   '/admin/v1/workflow/process-definitions/**',
     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (80, 'workflow:instance:manage',   '流程实例管理', 'API', 'POST',   '/admin/v1/workflow/process-instances/**',
     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (81, 'workflow:task:list',         '流程任务查询', 'API', 'GET',    '/admin/v1/workflow/tasks/**',
     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (82, 'workflow:task:operate',      '流程任务操作', 'API', 'POST',   '/admin/v1/workflow/tasks/**',
     'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色补绑新权限点（绑定行 ID 自 82 起续编；default 租户超管 role_id=1）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (82, 'default', 1, 78, now(), 'system', now(), 'system', 0, false),
    (83, 'default', 1, 79, now(), 'system', now(), 'system', 0, false),
    (84, 'default', 1, 80, now(), 'system', now(), 'system', 0, false),
    (85, 'default', 1, 81, now(), 'system', now(), 'system', 0, false),
    (86, 'default', 1, 82, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
