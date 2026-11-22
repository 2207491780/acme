-- 调度中心菜单种子（REQ-20261122-023213 确认④；幂等口径同 V13：条件 UPDATE + 旧值守卫，重放零行变更）。
-- /jobs 菜单叶（id=12，V19 种子，原挂「平台运营」目录 8）更新两处：
--   ① menu_name：「定时任务」→「调度中心」（域文案对齐页面标题/i18n/nav 键域改造）；
--   ② permission_code：infra:job:list → scheduler:job:list（V26 权限点 73；me.menus 动态形态随种子下发）。
-- path/component/icon/sort_order/parent_id/status 一律不动（路由 /jobs 复用，V13「人工改值保留」口径同源）。
-- 双语存储结构说明：sys_menu 无独立 en 文案列，既有菜单种子先例（V2/V19/V22/V24）均仅存 menu_name 单列，
-- zh 侧入库、en 侧由 web i18n 按菜单标识映射承载——本迁移沿用该结构，不新增列。
-- 幂等性：WHERE 守卫要求目标值 IS DISTINCT FROM 期望值，已应用（或人工改齐）时零行变更，重放不破坏；
-- version = version + 1 对齐 V13 审计口径。无新增权限点/绑定行（73~77 与超管绑定已由 V26 承担）。
UPDATE sys_menu
   SET menu_name       = '调度中心',
       permission_code = 'scheduler:job:list',
       updated_at      = now(),
       updated_by      = 'system',
       version         = version + 1
 WHERE id = 12
   AND path = '/jobs'
   AND deleted = false
   AND (menu_name IS DISTINCT FROM '调度中心'
        OR permission_code IS DISTINCT FROM 'scheduler:job:list');
