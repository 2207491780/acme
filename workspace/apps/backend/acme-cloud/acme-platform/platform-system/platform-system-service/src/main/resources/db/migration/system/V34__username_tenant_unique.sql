-- 用户名唯一域收敛（REQ-20261125-001738 确认①；版本预分配 V34——V33 由 REQ-001737 RLS 试点占用，禁用取号）。
-- sys_user 唯一键从全局 username 收敛为 (tenant_id, username)：同名用户可跨租户并存，
-- 同租户内不可重名；登录与画像查询按 (tenant_code, username) 域内定位（契约 §API）。
-- 软删占名语义与 V1 现状严格一致：V1 的 uk_sys_user_username = UNIQUE(username) 不含 deleted，
-- 故本迁移新约束同样不含 deleted（软删行继续占用域名），不引入 deleted 维度、不改既有数据。
-- 既有数据零冲突：全局唯一蕴含域内唯一，重放本脚本不产生重复键异常。
-- 幂等口径（照 V30/V33 先例）：目录判定守卫——旧约束存在才 DROP，新约束不存在才 ADD，重放零变化。

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_constraint
        WHERE conrelid = 'sys_user'::regclass
          AND conname = 'uk_sys_user_username'
          AND contype = 'u'
    ) THEN
        ALTER TABLE sys_user DROP CONSTRAINT uk_sys_user_username;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_constraint
        WHERE conrelid = 'sys_user'::regclass
          AND conname = 'uk_sys_user_tenant_username'
          AND contype = 'u'
    ) THEN
        ALTER TABLE sys_user
            ADD CONSTRAINT uk_sys_user_tenant_username UNIQUE (tenant_id, username);
    END IF;
END
$$;
