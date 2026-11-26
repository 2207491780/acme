-- 字典树形支持（REQ-20261125-230807 确认④；版本预分配 V35——V33/V34 已被 REQ-20261125-001737/001738 预留，禁用取号）。
-- 两变更（契约 §字典树形）：
--   sys_dict_type.dict_kind varchar(16) NOT NULL DEFAULT 'FLAT' —— 字典形态（FLAT 平铺|TREE 树形）；
--     PG ADD COLUMN NOT NULL DEFAULT 语义下存量行全量归 FLAT，无需独立回填，平铺行为零变化（回归锚点）。
--   sys_dict_item.parent_id bigint NULL —— 父条目（NULL=顶层；逻辑引用同类型 sys_dict_item.id，无 FK 对齐库内
--     惯例——同类型归属/存在/防环校验由 DictUseCase 承载，V30 先例口径）。
-- 幂等口径：照 V30/V31/V32 先例语句重放幂等（IF NOT EXISTS 承载）。

ALTER TABLE sys_dict_type ADD COLUMN IF NOT EXISTS dict_kind varchar(16) NOT NULL DEFAULT 'FLAT';

ALTER TABLE sys_dict_item ADD COLUMN IF NOT EXISTS parent_id bigint NULL;
