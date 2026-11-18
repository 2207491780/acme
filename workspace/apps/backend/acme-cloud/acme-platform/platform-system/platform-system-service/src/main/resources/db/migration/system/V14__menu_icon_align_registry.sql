-- V14：菜单种子 icon 收敛为 web 图标注册表标识（web/src/menuIcons.tsx，导航与选择器同源）。
-- V2/V3 种子沿用旧图标代码（setting/user/peoples 等），注册表未收录时导航回退宫格图标；
-- 仅更新仍处旧 icon 的种子行（菜单管理的人工改值保留）；id=8 monitor 已在注册表，不动。
UPDATE sys_menu SET icon = 'settings',    updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 1 AND icon = 'setting' AND deleted = false;
UPDATE sys_menu SET icon = 'users',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 2 AND icon = 'user'    AND deleted = false;
UPDATE sys_menu SET icon = 'roles',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 3 AND icon = 'peoples' AND deleted = false;
UPDATE sys_menu SET icon = 'permissions', updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 4 AND icon = 'key'     AND deleted = false;
UPDATE sys_menu SET icon = 'menus',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 5 AND icon = 'tree'    AND deleted = false;
UPDATE sys_menu SET icon = 'dicts',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 6 AND icon = 'dict'    AND deleted = false;
UPDATE sys_menu SET icon = 'tenants',     updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 7 AND icon = 'tenant'  AND deleted = false;
UPDATE sys_menu SET icon = 'audit',       updated_at = now(), updated_by = 'system', version = version + 1
 WHERE id = 9 AND icon = 'log'     AND deleted = false;