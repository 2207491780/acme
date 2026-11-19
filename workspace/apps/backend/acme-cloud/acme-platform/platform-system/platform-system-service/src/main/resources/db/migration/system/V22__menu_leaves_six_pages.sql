-- 六页菜单叶子补齐＋BUTTON 归位（REQ-20261119-151640；幂等口径同 V19/V20：
-- 固定主键 + ON CONFLICT (id) DO NOTHING；UPDATE 带旧 parent_id 守卫，口径同 V13）。
-- 菜单 ID 自 40 起续编（V20 用至 39）、role_menu 绑定行 ID 自 40 起续编（V20 用至 39）。
-- 无新权限点（权限点总数恒 68）——六页权限码均已存在：system:dept:list / system:position:list（V9）、
-- system:notice:list（V8）、infra:file:list（V7）、auth:loginlog:list / auth:online:list（V10）。
-- 目录归属按域分组（确认①）：depts/positions/notices 挂系统管理(1)，files/login-logs/online-users
-- 挂平台运营(8)；排序追加目录末尾（确认③）：dir1 sort=16/17/18，dir8 sort=5/6/7，不 UPDATE 既有叶子行。
-- component 列仅元数据（动态导航不消费），沿用既有「域/页/index」风格；icon 取 web 图标注册表标识（V14 口径）。
-- 角色绑定仅超管 role_id=1（确认②，与 V19/V20 一致），其他角色由管理员后续在授权树自行勾选。
-- BUTTON 归位（确认④）：V20 临时直挂目录的 9 个 BUTTON 节点迁挂对应新叶子——
-- 30~32→40（部门）、33~35→41（岗位）、36~37→42（公告）、38→43（文件）；叶内 sort_order 自 1 续编；
-- 旧 parent_id 守卫保幂等（重放不命中即空操作）。jobs 的 BUTTON 39 已挂叶子 12，不动。

-- 1. 六个页面 MENU 叶子（permission_code 关联既有权限点控制可见性）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort_order, visible,
                      permission_code, status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (40, 1, '部门管理', 'MENU', '/depts',        'system/dept/index',     'depts',       16, true,
     'system:dept:list',     'ENABLED', now(), 'system', now(), 'system', 0, false),
    (41, 1, '岗位管理', 'MENU', '/positions',    'system/position/index', 'positions',   17, true,
     'system:position:list', 'ENABLED', now(), 'system', now(), 'system', 0, false),
    (42, 1, '通知公告', 'MENU', '/notices',      'system/notice/index',   'notification', 18, true,
     'system:notice:list',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (43, 8, '文件管理', 'MENU', '/files',        'infra/file/index',      'files',       5,  true,
     'infra:file:list',      'ENABLED', now(), 'system', now(), 'system', 0, false),
    (44, 8, '登录日志', 'MENU', '/login-logs',   'auth/loginlog/index',   'loginlogs',   6,  true,
     'auth:loginlog:list',   'ENABLED', now(), 'system', now(), 'system', 0, false),
    (45, 8, '在线用户', 'MENU', '/online-users', 'auth/online/index',     'onlineusers', 7,  true,
     'auth:online:list',     'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色补绑新菜单（role_menu 绑定行 ID 自 40 起续编，节点 id 一一对应）
INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (40, 'default', 1, 40, now(), 'system', now(), 'system', 0, false),
    (41, 'default', 1, 41, now(), 'system', now(), 'system', 0, false),
    (42, 'default', 1, 42, now(), 'system', now(), 'system', 0, false),
    (43, 'default', 1, 43, now(), 'system', now(), 'system', 0, false),
    (44, 'default', 1, 44, now(), 'system', now(), 'system', 0, false),
    (45, 'default', 1, 45, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 3. BUTTON 归位：V20 临时直挂目录的 9 个节点迁挂对应新叶子（旧 parent_id 守卫，重放空操作）
UPDATE sys_menu SET parent_id = 40, sort_order = 1, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 30 AND parent_id = 1 AND deleted = false;
UPDATE sys_menu SET parent_id = 40, sort_order = 2, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 31 AND parent_id = 1 AND deleted = false;
UPDATE sys_menu SET parent_id = 40, sort_order = 3, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 32 AND parent_id = 1 AND deleted = false;
UPDATE sys_menu SET parent_id = 41, sort_order = 1, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 33 AND parent_id = 1 AND deleted = false;
UPDATE sys_menu SET parent_id = 41, sort_order = 2, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 34 AND parent_id = 1 AND deleted = false;
UPDATE sys_menu SET parent_id = 41, sort_order = 3, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 35 AND parent_id = 1 AND deleted = false;
UPDATE sys_menu SET parent_id = 42, sort_order = 1, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 36 AND parent_id = 1 AND deleted = false;
UPDATE sys_menu SET parent_id = 42, sort_order = 2, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 37 AND parent_id = 1 AND deleted = false;
UPDATE sys_menu SET parent_id = 43, sort_order = 1, updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 38 AND parent_id = 8 AND deleted = false;