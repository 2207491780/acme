-- 按钮级权限 BUTTON 菜单节点种子（REQ-20261119-051119 确认①；幂等口径同 V19：
-- 固定主键 + ON CONFLICT (id) DO NOTHING）。菜单 ID 自 13 起续编（V19 用至 12）、
-- role_menu 绑定行 ID 自 13 起续编（V19 用至 12）。无新权限点（权限点总数恒 68）——
-- BUTTON 节点 = 既有 API 权限点的授权管理投影（menu_type='BUTTON'，permission_code=页面现行码，
-- path/component/icon 恒空，visible=true）；角色授权菜单树勾选 BUTTON 节点经
-- RoleUseCase.bindMenus 联动同步 role_permission（确认②）。permission_code 逐条实地核对
-- 自 web 各页面 crud.can / CrudTable createPermission 现行调用码（2026-11-19）。
-- 父链口径：有页面菜单叶子的 6 页挂叶子（users=2/roles=3/dicts=6/menus=5/tenants=7/jobs=12）；
-- depts/positions/notices/files 四页 web 路由存在但 sys_menu 无菜单叶子（实地对照 V2~V19），
-- BUTTON 节点挂所属域目录（系统管理=1 / 平台运营=8），TreeBuilder 孤儿父链显式失败故不得造悬空 parent。
--
-- 节点 ↔ 权限码映射表（同码多操作合并为一节点，避免树勾选语义破碎）：
--   13 创建用户           system:user:create        users 新建按钮（createPermission）
--   14 编辑用户/状态切换   system:user:update        users 编辑按钮 + 启停 Popconfirm（同码）
--   15 删除用户           system:user:delete        users 删除按钮
--   16 用户角色分配       system:user:assign-role   users 角色分配按钮
--   17 创建角色           system:role:create        roles 新建按钮（createPermission）
--   18 编辑角色/状态切换   system:role:update        roles 编辑按钮 + 启停 Popconfirm（同码）
--   19 删除角色           system:role:delete        roles 删除按钮
--   20 权限/菜单授予       system:role:grant         roles 权限授予 + 菜单授予两按钮（同码）
--   21 角色数据范围       system:role:scope         roles 数据范围按钮
--   22 新增菜单/添加子级  system:menu:create        menus 新建 + 添加子级按钮（同码）
--   23 编辑菜单           system:menu:update        menus 编辑按钮
--   24 删除菜单           system:menu:delete        menus 删除按钮
--   25 新增字典（类型/项） system:dict:create        dicts 类型/项 新增按钮（同码）
--   26 编辑字典（类型/项） system:dict:update        dicts 类型/项 编辑按钮（同码）
--   27 删除字典（类型/项） system:dict:delete        dicts 类型/项 删除按钮（同码）
--   28 创建租户           system:tenant:create      tenants 新建按钮（createPermission）
--   29 编辑租户/停用      system:tenant:update      tenants 编辑按钮 + 启停 Popconfirm（同码）
--   30 新增部门           system:dept:create        depts 新建按钮（挂系统管理目录：页面无菜单叶子）
--   31 编辑部门           system:dept:update        depts 编辑按钮
--   32 删除部门           system:dept:delete        depts 删除按钮
--   33 新增岗位           system:position:create    positions 新建按钮（挂系统管理目录：页面无菜单叶子）
--   34 编辑岗位           system:position:update    positions 编辑按钮
--   35 删除岗位           system:position:delete    positions 删除按钮
--   36 公告发布/下线      system:notice:publish     notices 发布 + 下线按钮（同码；挂系统管理目录：页面无菜单叶子）
--   37 删除公告           system:notice:delete      notices 删除按钮
--   38 删除文件           infra:file:delete         files 删除按钮（挂平台运营目录：页面无菜单叶子）
--   39 手动执行任务       infra:job:run             jobs 手动执行按钮（挂定时任务叶子 12）

-- 1. BUTTON 节点（permission_code=既有 API 码，不新增 sys_permission 行）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort_order, visible,
                      permission_code, status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (13, 2,  '创建用户',           'BUTTON', NULL, NULL, NULL, 1,  true, 'system:user:create',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (14, 2,  '编辑用户/状态切换',   'BUTTON', NULL, NULL, NULL, 2,  true, 'system:user:update',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (15, 2,  '删除用户',           'BUTTON', NULL, NULL, NULL, 3,  true, 'system:user:delete',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (16, 2,  '用户角色分配',       'BUTTON', NULL, NULL, NULL, 4,  true, 'system:user:assign-role', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (17, 3,  '创建角色',           'BUTTON', NULL, NULL, NULL, 1,  true, 'system:role:create',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (18, 3,  '编辑角色/状态切换',   'BUTTON', NULL, NULL, NULL, 2,  true, 'system:role:update',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (19, 3,  '删除角色',           'BUTTON', NULL, NULL, NULL, 3,  true, 'system:role:delete',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (20, 3,  '权限/菜单授予',       'BUTTON', NULL, NULL, NULL, 4,  true, 'system:role:grant',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (21, 3,  '角色数据范围',       'BUTTON', NULL, NULL, NULL, 5,  true, 'system:role:scope',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (22, 5,  '新增菜单/添加子级',   'BUTTON', NULL, NULL, NULL, 1,  true, 'system:menu:create',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (23, 5,  '编辑菜单',           'BUTTON', NULL, NULL, NULL, 2,  true, 'system:menu:update',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (24, 5,  '删除菜单',           'BUTTON', NULL, NULL, NULL, 3,  true, 'system:menu:delete',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (25, 6,  '新增字典（类型/项）', 'BUTTON', NULL, NULL, NULL, 1,  true, 'system:dict:create',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (26, 6,  '编辑字典（类型/项）', 'BUTTON', NULL, NULL, NULL, 2,  true, 'system:dict:update',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (27, 6,  '删除字典（类型/项）', 'BUTTON', NULL, NULL, NULL, 3,  true, 'system:dict:delete',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (28, 7,  '创建租户',           'BUTTON', NULL, NULL, NULL, 1,  true, 'system:tenant:create',    'ENABLED', now(), 'system', now(), 'system', 0, false),
    (29, 7,  '编辑租户/停用',       'BUTTON', NULL, NULL, NULL, 2,  true, 'system:tenant:update',    'ENABLED', now(), 'system', now(), 'system', 0, false),
    (30, 1,  '新增部门',           'BUTTON', NULL, NULL, NULL, 8,  true, 'system:dept:create',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (31, 1,  '编辑部门',           'BUTTON', NULL, NULL, NULL, 9,  true, 'system:dept:update',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (32, 1,  '删除部门',           'BUTTON', NULL, NULL, NULL, 10, true, 'system:dept:delete',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (33, 1,  '新增岗位',           'BUTTON', NULL, NULL, NULL, 11, true, 'system:position:create',  'ENABLED', now(), 'system', now(), 'system', 0, false),
    (34, 1,  '编辑岗位',           'BUTTON', NULL, NULL, NULL, 12, true, 'system:position:update',  'ENABLED', now(), 'system', now(), 'system', 0, false),
    (35, 1,  '删除岗位',           'BUTTON', NULL, NULL, NULL, 13, true, 'system:position:delete',  'ENABLED', now(), 'system', now(), 'system', 0, false),
    (36, 1,  '公告发布/下线',       'BUTTON', NULL, NULL, NULL, 14, true, 'system:notice:publish',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (37, 1,  '删除公告',           'BUTTON', NULL, NULL, NULL, 15, true, 'system:notice:delete',    'ENABLED', now(), 'system', now(), 'system', 0, false),
    (38, 8,  '删除文件',           'BUTTON', NULL, NULL, NULL, 4,  true, 'infra:file:delete',       'ENABLED', now(), 'system', now(), 'system', 0, false),
    (39, 12, '手动执行任务',       'BUTTON', NULL, NULL, NULL, 1,  true, 'infra:job:run',           'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色（role_id=1）全绑新 BUTTON 节点（绑定行 ID 自 13 起续编，节点 id 一一对应）
INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (13, 'default', 1, 13, now(), 'system', now(), 'system', 0, false),
    (14, 'default', 1, 14, now(), 'system', now(), 'system', 0, false),
    (15, 'default', 1, 15, now(), 'system', now(), 'system', 0, false),
    (16, 'default', 1, 16, now(), 'system', now(), 'system', 0, false),
    (17, 'default', 1, 17, now(), 'system', now(), 'system', 0, false),
    (18, 'default', 1, 18, now(), 'system', now(), 'system', 0, false),
    (19, 'default', 1, 19, now(), 'system', now(), 'system', 0, false),
    (20, 'default', 1, 20, now(), 'system', now(), 'system', 0, false),
    (21, 'default', 1, 21, now(), 'system', now(), 'system', 0, false),
    (22, 'default', 1, 22, now(), 'system', now(), 'system', 0, false),
    (23, 'default', 1, 23, now(), 'system', now(), 'system', 0, false),
    (24, 'default', 1, 24, now(), 'system', now(), 'system', 0, false),
    (25, 'default', 1, 25, now(), 'system', now(), 'system', 0, false),
    (26, 'default', 1, 26, now(), 'system', now(), 'system', 0, false),
    (27, 'default', 1, 27, now(), 'system', now(), 'system', 0, false),
    (28, 'default', 1, 28, now(), 'system', now(), 'system', 0, false),
    (29, 'default', 1, 29, now(), 'system', now(), 'system', 0, false),
    (30, 'default', 1, 30, now(), 'system', now(), 'system', 0, false),
    (31, 'default', 1, 31, now(), 'system', now(), 'system', 0, false),
    (32, 'default', 1, 32, now(), 'system', now(), 'system', 0, false),
    (33, 'default', 1, 33, now(), 'system', now(), 'system', 0, false),
    (34, 'default', 1, 34, now(), 'system', now(), 'system', 0, false),
    (35, 'default', 1, 35, now(), 'system', now(), 'system', 0, false),
    (36, 'default', 1, 36, now(), 'system', now(), 'system', 0, false),
    (37, 'default', 1, 37, now(), 'system', now(), 'system', 0, false),
    (38, 'default', 1, 38, now(), 'system', now(), 'system', 0, false),
    (39, 'default', 1, 39, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
