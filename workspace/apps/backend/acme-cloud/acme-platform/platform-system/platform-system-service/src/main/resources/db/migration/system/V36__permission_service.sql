-- 权限点所属服务段（REQ-20261125-230807；版本预分配 V36——V33/V34 已被 REQ-20261125-001737/001738 预留，禁用取号）。
-- sys_permission + service varchar(32) NULL（flyway 维护目录列：Create/Update DTO 不接收、列表筛选不加，
--   AdminPermissionView 只读透出——契约 §sys_permission.service）。
-- 回填（契约钉死三口径）：
--   主=split_part(http_path,'/',4)（path 第 4 段，/admin/v1/<service>/…；87 条 API 种子路径 100% 可解析）；
--   兜底=permission_code 首段（无 http_path 行：BUTTON / 接口级权限点）；
--   归一=content → contents（content 域 permission_code 首段为 content、http_path 第 4 段为 contents，
--     同域两口径统一为 contents）。
-- 幂等口径：照 V30/V31/V32 先例语句重放幂等（DDL 用 IF NOT EXISTS；回填仅命中 service IS NULL 行，
--   归一语句重放零命中，重放零变化）。

ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS service varchar(32) NULL;

UPDATE sys_permission
SET service = split_part(http_path, '/', 4)
WHERE service IS NULL AND http_path IS NOT NULL AND split_part(http_path, '/', 4) <> '';

UPDATE sys_permission
SET service = split_part(permission_code, ':', 1)
WHERE service IS NULL AND permission_code IS NOT NULL AND split_part(permission_code, ':', 1) <> '';

UPDATE sys_permission SET service = 'contents' WHERE service = 'content';
