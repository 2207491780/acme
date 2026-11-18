-- 角色支持父子关系树状定义：sys_role 增 parent_id（根哨兵 0，与 sys_menu 同款；
-- 树形经 parentId + TreeBuilder 组装，无 ancestors/level 列、无 JPA 对象关联；12.3.3 schema 以迁移脚本为唯一来源）
-- 存量角色 parent_id 归 0（全部为根角色），层级关系此后由管理接口维护。

ALTER TABLE sys_role ADD COLUMN parent_id bigint NOT NULL DEFAULT 0;
CREATE INDEX idx_sys_role_parent ON sys_role (parent_id);
