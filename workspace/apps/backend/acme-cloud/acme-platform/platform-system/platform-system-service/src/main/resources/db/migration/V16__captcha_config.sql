-- 租户级验证码开关（REQ-20261117-011514）：sys_config 增 captcha_enabled 列，
-- B 端登录图形验证码改按租户策略条件校验（开启时校验、关闭时跳过；C 端短信验证码路径不涉及）。
-- 缺省关闭口径（契约确认记录①）：存量行、新增行、无行缺省视图均 captcha_enabled=false，
-- 无需数据回填；管理开关经既有 GET/PUT /admin/v1/system/config（system:config:update）随时切换。
-- 幂等口径：DDL 经 Flyway 版本化单次执行（同 V6 ALTER 口径，不使用 IF NOT EXISTS）；
-- 本迁移不新增权限点/菜单/绑定（captcha-policy 为公开端点与内部端点，不占权限点/菜单段位）。

ALTER TABLE sys_config ADD COLUMN captcha_enabled boolean NOT NULL DEFAULT false;
