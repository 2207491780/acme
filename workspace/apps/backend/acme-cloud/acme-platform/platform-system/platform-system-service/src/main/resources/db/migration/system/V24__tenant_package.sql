-- 租户套餐模型（REQ-20261121-022910；幂等口径同 V19/V22：固定主键 + ON CONFLICT (id) DO NOTHING）。
-- 三件 DDL（确认③）：sys_tenant_package（套餐主表，uk(package_name)）；sys_tenant_package_menu
-- （套餐-菜单规范化绑定表，uk(package_id,menu_id)）；sys_tenant 增 package_id bigint NULL
-- （NULL=不受限；无 FK 约束对齐库内惯例，逻辑校验）。审计列对齐 V1 AuditBase 惯例
-- （created_at/created_by/updated_at/updated_by/version/deleted）；绑定表同 7.2.7 口径
-- （deleted 恒 false，重绑物理解绑由服务层 FlexDeletes 旁路承载）。
-- 种子 ID 水位（确认⑥）：菜单 ID 自 46 起续编（V22 用至 45）、role_menu 绑定行 ID 自 46 起续编
-- （V22 用至 45）、权限点 ID 自 69 起续编（V19 用至 68）。
-- 套餐管理 MENU 叶挂系统管理目录（id=1，sort_order 顺延至 19——V22 目录内用至 18）；component 仅
-- 元数据（动态导航不消费），沿用「域/页/index」风格；icon 沿用 web 注册表既有 tenant 标识。
-- BUTTON×3 挂叶 46（新增/编辑/删除，V20 口径：path/component/icon 恒空、码为新权限点）；
-- role_menu 绑定仅 default 租户超管 role_id=1（与 V19/V20/V22 一致），其他角色由管理员在授权树
-- 自行勾选；sys_permission 4 点（API 类型；详情 GET /{id} 归 list 读语义，V7 先例）。
-- 超管角色补绑新权限点（role_permission 绑定行 ID 自 73 起续编，V19 用至 72）——
-- 「内置超管绑定全量权限点」为既有平台不变量（V2/V3/V4/V7/V8/V10/V15/V19 每次新增权限点均补绑，
-- SystemIntegrationTest seedScriptReplayIsIdempotent 与 MeButtonNodesTest 既有断言锁定），
-- 实施增补：契约确认⑥种子清单未列 role_permission 行，按既有种子惯例补齐（详见交付报告偏离清单）。

-- 1. 套餐主表
CREATE TABLE sys_tenant_package (
    id           bigint       PRIMARY KEY,
    package_name varchar(128) NOT NULL,
    remark       varchar(255),
    created_at   timestamptz  NOT NULL,
    created_by   varchar(64)  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    updated_by   varchar(64)  NOT NULL,
    version      bigint       NOT NULL,
    deleted      boolean      NOT NULL,
    CONSTRAINT uk_sys_tenant_package_name UNIQUE (package_name)
);

-- 2. 套餐-菜单绑定表（规范化绑定表；uk 复合唯一，package_id 前缀查询即绑定集读取）
CREATE TABLE sys_tenant_package_menu (
    id         bigint      PRIMARY KEY,
    package_id bigint      NOT NULL,
    menu_id    bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    created_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(64) NOT NULL,
    version    bigint      NOT NULL,
    deleted    boolean     NOT NULL,
    CONSTRAINT uk_sys_tenant_package_menu UNIQUE (package_id, menu_id)
);

-- 3. 租户绑定套餐列（NULL=不受限，行为与现状完全一致；绑定后菜单下发实时过滤，确认②）
ALTER TABLE sys_tenant ADD COLUMN package_id bigint NULL;
CREATE INDEX idx_sys_tenant_package ON sys_tenant (package_id);

-- 4. 权限点（API 类型，http_method + http_path 接口级；套餐管理五端点，detail 归 list 读语义）
INSERT INTO sys_permission (id, permission_code, permission_name, permission_type, http_method, http_path,
                            status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (69, 'system:tenant-package:list',   '套餐列表', 'API', 'GET',    '/admin/v1/system/tenant-packages',          'ENABLED', now(), 'system', now(), 'system', 0, false),
    (70, 'system:tenant-package:create', '创建套餐', 'API', 'POST',   '/admin/v1/system/tenant-packages',          'ENABLED', now(), 'system', now(), 'system', 0, false),
    (71, 'system:tenant-package:update', '修改套餐', 'API', 'PUT',    '/admin/v1/system/tenant-packages/{id}',     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (72, 'system:tenant-package:delete', '删除套餐', 'API', 'DELETE', '/admin/v1/system/tenant-packages/{id}',     'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 5. 套餐管理菜单（MENU 叶挂系统管理目录 id=1 + BUTTON×3 挂叶 46；码关联权限点控制可见性）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort_order, visible,
                      permission_code, status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (46, 1, '套餐管理', 'MENU',   '/tenant-packages', 'system/package/index', 'tenant', 19, true,
     'system:tenant-package:list', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (47, 46, '创建套餐', 'BUTTON', NULL, NULL, NULL, 1, true,
     'system:tenant-package:create', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (48, 46, '编辑套餐', 'BUTTON', NULL, NULL, NULL, 2, true,
     'system:tenant-package:update', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (49, 46, '删除套餐', 'BUTTON', NULL, NULL, NULL, 3, true,
     'system:tenant-package:delete', 'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 6. 超管角色补绑新菜单（role_menu 绑定行 ID 自 46 起续编，节点 id 一一对应，default 租户超管 role_id=1）
INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (46, 'default', 1, 46, now(), 'system', now(), 'system', 0, false),
    (47, 'default', 1, 47, now(), 'system', now(), 'system', 0, false),
    (48, 'default', 1, 48, now(), 'system', now(), 'system', 0, false),
    (49, 'default', 1, 49, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 7. 超管角色补绑新权限点（绑定行 ID 自 73 起续编，V19 用至 72；不变量见文件头注）
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (73, 'default', 1, 69, now(), 'system', now(), 'system', 0, false),
    (74, 'default', 1, 70, now(), 'system', now(), 'system', 0, false),
    (75, 'default', 1, 71, now(), 'system', now(), 'system', 0, false),
    (76, 'default', 1, 72, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
