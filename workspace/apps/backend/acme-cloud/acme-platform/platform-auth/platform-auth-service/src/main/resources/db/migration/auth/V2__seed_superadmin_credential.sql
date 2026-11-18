-- 超管凭据种子（FR-009，Q-D05 裁定 A：Flyway placeholder 环境注入 + pgcrypto BCrypt，
-- 密码明文与散列均不落仓库；重复执行幂等（固定主键 + ON CONFLICT，AC-009）。
-- user_id=1 对应 platform-system V2 种子超管用户；BCrypt $2a 前缀与 Java 校验端兼容（实现说明登记）。

-- pgcrypto 固定装 public（dev-init / 生产等价部署步骤）；连接 search_path 仅服务 schema，
-- 函数须显式限定 public.*，否则解析失败（本服务 schema 种子在真实连接下不可见 public 函数）。
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

INSERT INTO auth_credential (id, user_id, tenant_id, password_hash, password_updated_at,
                             created_at, created_by, updated_at, updated_by, version, deleted)
VALUES (1, 1, 'default', public.crypt('${seedSuperadminPassword}', public.gen_salt('bf', 10)), now(),
        now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
