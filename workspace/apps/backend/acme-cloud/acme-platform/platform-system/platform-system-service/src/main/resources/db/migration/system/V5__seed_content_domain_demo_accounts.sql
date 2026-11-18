-- 内容域权限点 + B 端演示账号种子（business-api-integration，架构设计 3.6/3.7，QD-07 已裁定）：
-- ①内容域权限点 5 条（与 business-content-api ContentPermissions 常量一一对应）；
-- ②超管角色（role_id=1）补绑新权限点（延续「内置超管绑定全量权限点」口径，V2 注释）；
-- ③演示账号种子（RBAC 机制内的角色/账号种子记录，不新建角色体系）：demo_admin 绑 super_admin、
--   demo_operator 绑受限角色 content_operator（无 batch-delete，演示受限权限口径）。
-- 幂等口径同 V2：固定主键 + ON CONFLICT (id) DO NOTHING；权限点 ID 自 34 起（V2 用 1-31、V3 用 32、V4 用 33）。
-- 密码凭据归 platform-auth V4（本表不含任何凭据形态，V2 注释口径）。

-- 1. 内容域权限点（API 类型，HTTP 面登记）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (34, 'content:content:list',           '内容查询',   'API', 'GET',   '/admin/v1/contents',                 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (35, 'content:content:update',         '内容标题修改', 'API', 'PATCH', '/admin/v1/contents/{contentId}/title', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (36, 'content:content:transition',     '内容状态流转', 'API', 'PUT',   '/admin/v1/contents/{contentId}/status', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (37, 'content:content:batch-offline',  '内容批量下线', 'API', 'POST',  '/admin/v1/contents/batch',           'ENABLED', now(), 'system', now(), 'system', 0, false),
    (38, 'content:content:batch-delete',   '内容批量删除', 'API', 'POST',  '/admin/v1/contents/batch',           'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色补绑新权限点（绑定行 ID 自 34 起续编）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (34, 'default', 1, 34, now(), 'system', now(), 'system', 0, false),
    (35, 'default', 1, 35, now(), 'system', now(), 'system', 0, false),
    (36, 'default', 1, 36, now(), 'system', now(), 'system', 0, false),
    (37, 'default', 1, 37, now(), 'system', now(), 'system', 0, false),
    (38, 'default', 1, 38, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 3. 受限运营角色（QD-07 边界注记：既有 RBAC 机制内的种子记录，built_in=false 可维护）
INSERT INTO sys_role (id, tenant_id, role_code, role_name, status, remark, built_in,
                      created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (2, 'default', 'content_operator', '内容运营', 'ENABLED', '演示受限运营角色（business-api-integration 种子）', false,
        now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 4. content_operator 权限绑定（list/update/transition/batch-offline，无 batch-delete）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (39, 'default', 2, 34, now(), 'system', now(), 'system', 0, false),
    (40, 'default', 2, 35, now(), 'system', now(), 'system', 0, false),
    (41, 'default', 2, 36, now(), 'system', now(), 'system', 0, false),
    (42, 'default', 2, 37, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 5. 演示账号（昵称对齐 demo_accounts.dart：陈序 / 吴限；用户名 QD-07 裁定）
INSERT INTO sys_user (id, tenant_id, username, nickname, email, phone, status, built_in,
                      created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (2, 'default', 'demo_admin',    '陈序', NULL, NULL, 'ENABLED', false, now(), 'system', now(), 'system', 0, false),
    (3, 'default', 'demo_operator', '吴限', NULL, NULL, 'ENABLED', false, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 6. 用户-角色绑定（demo_admin → super_admin；demo_operator → content_operator）
INSERT INTO sys_user_role (id, tenant_id, user_id, role_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (2, 'default', 2, 1, now(), 'system', now(), 'system', 0, false),
    (3, 'default', 3, 2, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
