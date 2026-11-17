-- 租户级水印模板（REQ-20261117-180000）：sys_config 增水印模板列。
-- 列语义：
--   sys_config.watermark_template varchar(255) NULL —— 水印内容模板（可空=未配置，
--                             前端缺省仅渲染登录用户名，沿用 REQ-20261116-230354 现状）；
--                             占位符 {username}/{nickname}/{userId}/{tenantCode}/{deptName}/{date}/{time}，
--                             未知占位符在用例层拒绝（SYSTEM_WATERMARK_TEMPLATE_INVALID）。
-- 可空列不回填：存量配置行 = 未配置模板，符合缺省口径。
-- 管理面沿用既有权限点 system:config:update（随既有 upsert 全量生效），
-- 本迁移无新增权限点/菜单/绑定。
-- 幂等口径：DDL 经 Flyway 版本化单次执行（同 V17 ALTER 口径，不使用 IF NOT EXISTS）。

ALTER TABLE sys_config
    ADD COLUMN watermark_template varchar(255) NULL;