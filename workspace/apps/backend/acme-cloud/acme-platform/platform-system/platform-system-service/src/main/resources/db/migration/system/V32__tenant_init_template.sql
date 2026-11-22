-- 租户开通初始化模板 + 平台运营视图权限（REQ-20261122-233426 确认②⑤；版本预分配 V32——
-- V31 已被 TENANT-4 生命周期配额占用，禁用 V31 取号）。
-- 两变更（契约 §变更清单 §数据）：
--   sys_tenant_init_template 新表 —— 开通模板（id/code uk/name/remark/content jsonb/
--     default_package_id?/审计列）。content 快照语义（确认②④）：形状
--     {adminUsernamePrefix?, adminNickname?, rolePresets:[{roleKey,roleName,permissionCodes[]}],
--     configEntries:[{configKey,value}]}——开通时按模板快照建角色集/绑授权/写 sys_config/
--     默认套餐，非引用绑定（与套餐下发模型刻意区分，模板后改不影响已开通租户）。
--     实体侧 String 经 JsonbTypeHandler 映射（sys_user_preference.prefs 同款，V23 先例）。
--     平台级目录表（无 tenant_id 列、不参与租户过滤，sys_tenant_package 同款 Q-D04 口径）。
--   5 权限点种子（坑 16 流程）—— system:tenant-template:list/create/update/delete +
--     system:tenant:usage-overview，挂靠租户管理既有菜单（id=7「租户管理」，path
--     /system/tenants，V2 种子；本迁移【不新增菜单节点】：契约确认⑤钉死 BUTTON/MENU
--     总数不变（55/33 水位），新权限点以纯 API 类型登记（V26 调度域 73~77 先例——
--     无菜单节点的接口级权限点），前端显隐经 me.permissionCodes 判定）。
--     超管角色绑定随种子幂等写入（「内置超管绑定全量权限点」为既有平台不变量，
--     V2/V3/V4/V7/V8/V10/V15/V19/V24/V26/V27 每次新增权限点均补绑）。
-- 幂等口径：照 V30/V31 先例语句重放幂等（AC-1 显式钉死执行+重放+幂等，DDL 用
-- IF NOT EXISTS、种子用固定主键 + ON CONFLICT (id) DO NOTHING 承载）。
-- 种子 ID 水位：sys_permission 自 83 起续编（V27 用至 82）、sys_role_permission 绑定行自 87
-- 起续编（V27 用至 86）；无 sys_menu / sys_role_menu 行（计数水位不变）。
-- 存量不动：无模板种子行（模板由管理员经 CRUD 端点创建）、存量租户/角色/绑定零改动。

-- 1. 开通模板主表（content jsonb NOT NULL；default_package_id 可空=不设默认套餐）
CREATE TABLE IF NOT EXISTS sys_tenant_init_template (
    id                 bigint       PRIMARY KEY,
    code               varchar(64)  NOT NULL,
    name               varchar(128) NOT NULL,
    remark             varchar(255),
    content            jsonb        NOT NULL,
    default_package_id bigint       NULL,
    created_at         timestamptz  NOT NULL,
    created_by         varchar(64)  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    updated_by         varchar(64)  NOT NULL,
    version            bigint       NOT NULL,
    deleted            boolean      NOT NULL,
    CONSTRAINT uk_sys_tenant_init_template_code UNIQUE (code)
);

-- 2. 权限点（API 类型，http_method + http_path 接口级；挂靠租户管理菜单 id=7，无菜单节点）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (83, 'system:tenant-template:list',   '开通模板列表', 'API', 'GET',    '/admin/v1/system/tenant-templates',           'ENABLED', now(), 'system', now(), 'system', 0, false),
    (84, 'system:tenant-template:create', '创建开通模板', 'API', 'POST',   '/admin/v1/system/tenant-templates',           'ENABLED', now(), 'system', now(), 'system', 0, false),
    (85, 'system:tenant-template:update', '修改开通模板', 'API', 'PUT',    '/admin/v1/system/tenant-templates/{id}',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (86, 'system:tenant-template:delete', '删除开通模板', 'API', 'DELETE', '/admin/v1/system/tenant-templates/{id}',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (87, 'system:tenant:usage-overview',  '租户运营总览', 'API', 'GET',    '/admin/v1/system/tenants/usage-overview',     'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 3. 超管角色补绑新权限点（绑定行 ID 自 87 起续编，V27 用至 86；不变量见文件头注）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (87, 'default', 1, 83, now(), 'system', now(), 'system', 0, false),
    (88, 'default', 1, 84, now(), 'system', now(), 'system', 0, false),
    (89, 'default', 1, 85, now(), 'system', now(), 'system', 0, false),
    (90, 'default', 1, 86, now(), 'system', now(), 'system', 0, false),
    (91, 'default', 1, 87, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
