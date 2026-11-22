-- 流程中心菜单种子（REQ-20261122-102730 确认③/AC-10；幂等口径同 V22/V24：固定主键 +
-- ON CONFLICT (id) DO NOTHING，重放零行变更）。无新权限点（坑 16 计数不变）——
-- 所有菜单行挂 V27 既有 API 权限点 78~82，V27 已补超管 role_permission 绑定，本迁移不触
-- sys_permission / sys_role_permission。
-- 种子 ID 水位：菜单 ID 自 50 起续编（V24 用至 46~49；V26~V28 无菜单行）、role_menu 绑定行
-- ID 自 50 起续编（V24 用至 49）。
-- 树形状（确认③）：「流程中心」DIRECTORY（顶层，path /workflow-center 不与叶路由冲突，
-- sort 顺延顶层目录 V3 用至 2）+「流程定义」叶（path /workflow，permission workflow:definition:list 78，
-- 实例监控 Tab 归属本页）+「待办中心」叶（path /workflow/todos，permission workflow:task:list 81，
-- 独立路由页）+ BUTTON 行×3：部署 79 / 实例管理 80 挂流程定义叶（51）、任务操作 82 挂待办中心叶（52）
-- ——BUTTON 不进导航仅权限投影，V20/V24 先例：path/component/icon 恒空、码关联既有权限点。
-- component 列仅元数据（动态导航不消费），沿用「域/页/index」风格；icon 占位沿用域标识
-- workflow（web 侧如注册表无此标识，由前端 BPM-3 消费时对齐，不影响后端下发形状）。
-- 角色绑定仅 default 租户超管 role_id=1（V19/V20/V22/V24 一致），绑定行 ID 与菜单 id 一一对应。
-- 双语存储结构说明同 V28：sys_menu 仅存 menu_name 单列，zh 入库、en 由 web i18n 承载。

-- 1. 流程中心菜单树（DIRECTORY + 两 MENU 叶 + BUTTON×3）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, icon, sort_order, visible,
                      permission_code, status, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (50, 0,  '流程中心', 'DIRECTORY', '/workflow-center', NULL,               'workflow', 3, true,
     NULL,                          'ENABLED', now(), 'system', now(), 'system', 0, false),
    (51, 50, '流程定义', 'MENU',      '/workflow',        'workflow/index',   'workflow', 1, true,
     'workflow:definition:list',    'ENABLED', now(), 'system', now(), 'system', 0, false),
    (52, 50, '待办中心', 'MENU',      '/workflow/todos',  'workflow/todo/index', 'workflow', 2, true,
     'workflow:task:list',          'ENABLED', now(), 'system', now(), 'system', 0, false),
    (53, 51, '部署定义', 'BUTTON',     NULL, NULL, NULL, 1, true,
     'workflow:definition:deploy',  'ENABLED', now(), 'system', now(), 'system', 0, false),
    (54, 51, '实例管理', 'BUTTON',     NULL, NULL, NULL, 2, true,
     'workflow:instance:manage',    'ENABLED', now(), 'system', now(), 'system', 0, false),
    (55, 52, '任务操作', 'BUTTON',     NULL, NULL, NULL, 1, true,
     'workflow:task:operate',       'ENABLED', now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 超管角色补绑新菜单（role_menu 绑定行 ID 自 50 起续编，节点 id 一一对应，default 租户超管 role_id=1）
INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id,
                           created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (50, 'default', 1, 50, now(), 'system', now(), 'system', 0, false),
    (51, 'default', 1, 51, now(), 'system', now(), 'system', 0, false),
    (52, 'default', 1, 52, now(), 'system', now(), 'system', 0, false),
    (53, 'default', 1, 53, now(), 'system', now(), 'system', 0, false),
    (54, 'default', 1, 54, now(), 'system', now(), 'system', 0, false),
    (55, 'default', 1, 55, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
