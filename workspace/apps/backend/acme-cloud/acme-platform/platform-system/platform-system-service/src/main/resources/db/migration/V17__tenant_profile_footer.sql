-- 租户机制补强＋租户页脚版权声明（REQ-20261117-025200）：sys_tenant 增联系人 / 有效期三列，
-- sys_config 增页脚版权两列。
-- 列语义：
--   sys_tenant.contact_name  varchar(64) NULL —— 联系人姓名（可空=未配置）；
--   sys_tenant.contact_phone varchar(32) NULL —— 联系人电话（可空=未配置）；
--   sys_tenant.expires_at    timestamptz NULL —— 租户有效期（可空=长期有效；过期租户登录拒绝、
--                             登录下拉排除、不可作切换目标，确认记录③④）；
--   sys_config.footer_copyright varchar(255) NULL —— 页脚版权文案（可空=未配置）；
--   sys_config.footer_visible  boolean NOT NULL DEFAULT false —— 页脚是否显示（缺省不显示，
--                             未配置租户公开视图返回 copyright=null / visible=false，确认记录⑤）。
-- 可空列不回填：存量租户 = 无联系人 / 长期有效，存量配置行页脚不显示，符合缺省视图口径。
-- 管理面沿用既有权限点（确认记录⑥）：租户字段 system:tenant:update、页脚 system:config:update，
-- 本迁移无新增权限点/菜单/绑定（by-code / public-view 为 internal 端点，页脚公开查询在 auth 侧，
-- 均不占权限点/菜单段位）。
-- 幂等口径：DDL 经 Flyway 版本化单次执行（同 V16 ALTER 口径，不使用 IF NOT EXISTS）。

ALTER TABLE sys_tenant
    ADD COLUMN contact_name varchar(64) NULL,
    ADD COLUMN contact_phone varchar(32) NULL,
    ADD COLUMN expires_at timestamptz NULL;

ALTER TABLE sys_config
    ADD COLUMN footer_copyright varchar(255) NULL,
    ADD COLUMN footer_visible boolean NOT NULL DEFAULT false;
