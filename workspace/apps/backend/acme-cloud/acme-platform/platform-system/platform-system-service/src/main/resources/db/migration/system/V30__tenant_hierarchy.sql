-- 子租户层级（REQ-20261122-102954 确认②；版本预分配 V30——V29 已被 BPM-3 菜单种子占用，禁用 V29 取号）。
-- sys_tenant ADD parent_id bigint NULL（NULL=顶层，逻辑引用 sys_tenant.id，无 FK 对齐库内惯例——
-- 逻辑校验由 TenantUseCase 承载）+ level int NOT NULL DEFAULT 1（1=顶层，上限 3；物化列，
-- 一致性由改父/级联停用写路径保证，解析零层级感知）。
-- 存量回填：PG ADD COLUMN NOT NULL DEFAULT 1 语义下全量既有行即得 level=1，无需独立 UPDATE。
-- idx(parent_id) 支撑后代遍历（环检测/深度校验/改父 level 重算/级联停用/tree 组装）。
-- tenant_code 全局唯一语义不变；语句重放幂等（AC-1「执行+重放+幂等」，IF NOT EXISTS 承载——
-- 偏离 V24 无 IF NOT EXISTS 先例，系 AC-1 显式钉死重放口径，AMB-2 实现侧裁决落此）。

ALTER TABLE sys_tenant ADD COLUMN IF NOT EXISTS parent_id bigint NULL;
ALTER TABLE sys_tenant ADD COLUMN IF NOT EXISTS level int NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_sys_tenant_parent ON sys_tenant (parent_id);
