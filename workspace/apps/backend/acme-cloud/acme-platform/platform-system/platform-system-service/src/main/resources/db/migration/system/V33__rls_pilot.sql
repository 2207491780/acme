-- 多租户数据库 RLS 兜底一期（REQ-20261125-001737 确认②⑤⑥；版本预分配 V33，V34 由 REQ-001738 预留）。
-- 试点表 = system 域带 varchar(64) tenant_id（租户编码）的 13 张表：9 张业务表
--   sys_user / sys_role / sys_dept / sys_position / sys_dict_type / sys_dict_item /
--   sys_config / sys_notice / sys_message，及 4 张绑定表
--   sys_user_role / sys_role_permission / sys_role_menu / sys_role_dept。
-- 平台目录表（sys_tenant / sys_permission / sys_menu / sys_tenant_package / sys_tenant_init_template /
--   sys_tenant_quota / sys_tenant_package_menu / sys_capability / ...）无租户编码列，不启用 RLS（确认③）。
-- 策略口径（确认②）：USING 与 WITH CHECK 均为
--   tenant_id = current_setting('app.tenant_id', true)
--   OR current_setting('app.tenant_bypass', true) = 'on'
--   会话变量未设置 → NULL → 不匹配任何行（fail-closed，与 ORM 哨兵口径一致）。
-- FORCE ROW LEVEL SECURITY：表 owner 同样受策略约束；superuser 例外——故 PG 集成铁证用例
--   必须用本迁移创建的非超管应用角色 acme_rls_app 连接（容器场景角色随库生灭）。
-- 幂等口径（照 V30/V31 先例）：IF NOT EXISTS 建角色 + DROP POLICY IF EXISTS 后重建策略，重放不报错不重复建。

-- 1. 非超管应用角色（NOSUPERUSER/NOBYPASSRLS 为 CREATE ROLE 缺省；供 PG IT 直连验证 RLS）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'acme_rls_app') THEN
        CREATE ROLE acme_rls_app LOGIN PASSWORD 'acme_rls_app_pwd';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO acme_rls_app;

-- 2. 逐表授权 + ENABLE/FORCE RLS + 策略重建（枚举即试点范围权威清单）
DO $$
DECLARE
    pilot_table text;
BEGIN
    FOREACH pilot_table IN ARRAY ARRAY[
        'sys_user', 'sys_role', 'sys_dept', 'sys_position',
        'sys_dict_type', 'sys_dict_item', 'sys_config', 'sys_notice', 'sys_message',
        'sys_user_role', 'sys_role_permission', 'sys_role_menu', 'sys_role_dept'
    ]
    LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON %I TO acme_rls_app', pilot_table);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', pilot_table);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', pilot_table);
        EXECUTE format('DROP POLICY IF EXISTS rls_tenant_isolation ON %I', pilot_table);
        EXECUTE format($policy$
            CREATE POLICY rls_tenant_isolation ON %I
                USING (
                    tenant_id = current_setting('app.tenant_id', true)
                    OR current_setting('app.tenant_bypass', true) = 'on'
                )
                WITH CHECK (
                    tenant_id = current_setting('app.tenant_id', true)
                    OR current_setting('app.tenant_bypass', true) = 'on'
                )
        $policy$, pilot_table);
    END LOOP;
END
$$;
