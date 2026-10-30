-- platform-services-bootstrap 本地开发建库脚本（架构设计 9.6；仅本地开发用，不入生产路径——
-- 生产由部署流程执行等价步骤；.dev-stack PostgreSQL 00-init.sql 不预置服务库，按 18.1.1 第 7 步自建）。
-- 执行方式（psql 连 .dev-stack pgsql，任意管理员账号）：
--   psql -h localhost -U $PG_USER -d $PG_DB -f backend/dev-init/00-platform-schemas.sql
-- 各服务 schema 内的表结构由 Flyway 版本化迁移脚本建立（12.3.3），本脚本仅建 schema 与账号。

-- 每服务独占 schema（12.1.1）+ 独立账号（12.1.3：账号仅授权自有 schema，禁止跨服务访问）
CREATE SCHEMA IF NOT EXISTS platform_auth;
CREATE SCHEMA IF NOT EXISTS platform_system;

-- 本地开发专用账号（生产取值由部署环境注入，14.3.3；本地口令仅开发用）
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'acme_auth') THEN
        CREATE ROLE acme_auth LOGIN PASSWORD 'acme_auth_dev';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'acme_system') THEN
        CREATE ROLE acme_system LOGIN PASSWORD 'acme_system_dev';
    END IF;
END
$$;

GRANT USAGE, CREATE ON SCHEMA platform_auth TO acme_auth;
GRANT USAGE, CREATE ON SCHEMA platform_system TO acme_system;
ALTER ROLE acme_auth SET search_path = platform_auth;
ALTER ROLE acme_system SET search_path = platform_system;

-- pgcrypto：platform-auth 超管凭据种子（V2）使用 crypt/gen_salt（Q-D05）
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
GRANT USAGE ON SCHEMA public TO acme_auth, acme_system;

-- platform-infra 增补（platform-infra-audit 拆分评审；补登记 2026-10-26 consumer-auth-refresh-dataperm 顺带修补）
CREATE SCHEMA IF NOT EXISTS platform_infra;

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'acme_infra') THEN
        CREATE ROLE acme_infra LOGIN PASSWORD 'acme_infra_dev';
    END IF;
END
$$;

GRANT USAGE, CREATE ON SCHEMA platform_infra TO acme_infra;
ALTER ROLE acme_infra SET search_path = platform_infra;
GRANT USAGE ON SCHEMA public TO acme_infra;

-- business-content 增补（business-api-integration G2.5 拆分评审；18.1.1 第 7 步独立 schema）
CREATE SCHEMA IF NOT EXISTS business_content;

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'acme_content') THEN
        CREATE ROLE acme_content LOGIN PASSWORD 'acme_content_dev';
    END IF;
END
$$;

GRANT USAGE, CREATE ON SCHEMA business_content TO acme_content;
ALTER ROLE acme_content SET search_path = business_content;
GRANT USAGE ON SCHEMA public TO acme_content;
