-- RLS 租户隔离收口（REQ-20261127-234335 B1 P0 安全 V-01；决策⑥：全环境无条件 NOLOGIN + test 联调口令
-- 经测试配置注入重开；旁路判据另绑可信主体）。
-- V33__rls_pilot.sql 因 Flyway 校验和固化禁改，本迁移在其后收口两处缺口：
-- 1. 铁证角色 acme_rls_app 明文口令（V33 创建时内联 PASSWORD）：收口为 NOLOGIN + 口令清空（生产形态）。
--    PG 角色是集群级属性，本迁移对 prod/dev/test 全部生效；零 Docker PG IT 夹具在 Flyway 迁移完成后、
--    应用连接首次使用前，经配置注入的联调口令（属性 acme.rls.it.role-password / 环境变量
--    ACME_RLS_IT_ROLE_PASSWORD）以嵌入式 PG 超管参数化重开 LOGIN——迁移 SQL 内不做环境分支、不写死口令。
-- 2. 旁路判据仅凭会话可自设 GUC：V33 策略的旁路分支 current_setting('app.tenant_bypass', true) = 'on'
--    可被任何能建立 PG 会话的主体自设，即跨租户读写（Tier0 安全 Veto V-01）。收口为「自设 GUC + 可信角色
--    成员」联合判定：新增 NOLOGIN 角色 acme_rls_bypass，旁路分支追加
--    pg_has_role(current_user, 'acme_rls_bypass', 'member')。框架侧 GUC 写入点唯一
--    （RlsPropagationSupport，契约确认④零 Java 改动），合法旁路（@IgnoreTenant → TenantBypassScope）
--    经下方授权矩阵（acme_rls_bypass 授予 acme_rls_app / acme）继续生效；非成员主体自设 GUC
--    不再获得任何额外行（旁路分支单独失效，fail-closed）。
-- 授权矩阵：acme_rls_bypass 授予 acme_rls_app（IT 铁证路径）；acme（prod 应用连接角色）授予以
--   存在性守卫包裹——嵌入式 PG IT 集群无该角色，跳过即正确路径。
-- 幂等口径（照 V33 先例）：ALTER 天然幂等 + IF NOT EXISTS 建角色 + DROP POLICY IF EXISTS 后重建策略，
--   重放不报错不重复建；13 张试点表枚举以 V33 为唯一权威清单。

-- 1. 铁证角色收口：NOLOGIN + 口令清空（旧口令在 git 历史与已部署环境的残留由密钥扫描移交项覆盖）
ALTER ROLE acme_rls_app NOLOGIN;
ALTER ROLE acme_rls_app PASSWORD NULL;

-- 2. 可信旁路角色（NOLOGIN；NOSUPERUSER/NOCREATEROLE 为 CREATE ROLE 缺省，与 V33 角色创建写法对齐）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'acme_rls_bypass') THEN
        CREATE ROLE acme_rls_bypass NOLOGIN;
    END IF;
END
$$;

-- 3. 授权矩阵：acme_rls_bypass 授予 acme_rls_app（角色已由 V33/段1 前提保证存在，无条件授）；
--    acme 仅在角色存在时授（prod/dev 形态；IT 嵌入式集群跳过）
GRANT acme_rls_bypass TO acme_rls_app;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'acme') THEN
        GRANT acme_rls_bypass TO acme;
    END IF;
END
$$;

-- 4. 策略重建：USING 与 WITH CHECK 同式（联合判定），13 张试点表枚举照 V33
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
        EXECUTE format('DROP POLICY IF EXISTS rls_tenant_isolation ON %I', pilot_table);
        EXECUTE format($policy$
            CREATE POLICY rls_tenant_isolation ON %I
                USING (
                    tenant_id = current_setting('app.tenant_id', true)
                    OR (
                        current_setting('app.tenant_bypass', true) = 'on'
                        AND pg_has_role(current_user, 'acme_rls_bypass', 'member')
                    )
                )
                WITH CHECK (
                    tenant_id = current_setting('app.tenant_id', true)
                    OR (
                        current_setting('app.tenant_bypass', true) = 'on'
                        AND pg_has_role(current_user, 'acme_rls_bypass', 'member')
                    )
                )
        $policy$, pilot_table);
    END LOOP;
END
$$;
