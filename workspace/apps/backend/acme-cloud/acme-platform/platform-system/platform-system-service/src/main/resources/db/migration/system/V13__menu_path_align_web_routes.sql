-- V13：菜单种子 path 收敛为 web 既有路由（动态导航契约：叶子 path = 路由路径，web/src/nav.tsx）。
-- V2/V3 种子沿用后端目录式 path（/system/users 等），点击动态菜单落 wildcard 路由秒回首页；
-- 仅更新仍处旧 path 的种子行（菜单管理的人工改值保留）；目录不参与导航，path 不动。
UPDATE sys_menu SET path = '/users',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 2 AND path = '/system/users'       AND deleted = false;
UPDATE sys_menu SET path = '/roles',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 3 AND path = '/system/roles'       AND deleted = false;
UPDATE sys_menu SET path = '/permissions', updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 4 AND path = '/system/permissions' AND deleted = false;
UPDATE sys_menu SET path = '/menus',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 5 AND path = '/system/menus'       AND deleted = false;
UPDATE sys_menu SET path = '/dicts',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 6 AND path = '/system/dicts'       AND deleted = false;
UPDATE sys_menu SET path = '/tenants',     updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 7 AND path = '/system/tenants'     AND deleted = false;
UPDATE sys_menu SET path = '/audit',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 9 AND path = '/infra/audit-events' AND deleted = false;