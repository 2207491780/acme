-- B 端演示账号凭据种子（business-api-integration，架构设计 3.6/3.7）：
-- user_id=2/3 对应 platform-system V5 演示账号（demo_admin / demo_operator）。
-- 密码经 Flyway placeholder 环境注入 + pgcrypto BCrypt（明文与散列均不落仓库，14.3.3，platform-auth V2 先例）；
-- 无仓库内缺省值——联调/生产经部署环境注入 ACME_SEED_DEMO_ADMIN_PASSWORD / ACME_SEED_DEMO_OPERATOR_PASSWORD。
-- 幂等口径同 V2：固定主键 + ON CONFLICT (id) DO NOTHING。

-- pgcrypto 固定装 public（V2 同口径）；连接 search_path 仅服务 schema，函数须显式限定 public.*
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

INSERT INTO auth_credential (id, user_id, tenant_id, password_hash, password_updated_at,
                             created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (2, 2, 'default', public.crypt('${seedDemoAdminPassword}', public.gen_salt('bf', 10)), now(),
        now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO auth_credential (id, user_id, tenant_id, password_hash, password_updated_at,
                             created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (3, 3, 'default', public.crypt('${seedDemoOperatorPassword}', public.gen_salt('bf', 10)), now(),
        now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
