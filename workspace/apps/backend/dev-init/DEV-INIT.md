# dev-init — 平台服务本地开发初始化脚本

本地开发（`.dev-stack` PostgreSQL）一次性初始化：建 `platform_auth` / `platform_system` 独立 schema
与对应数据库账号（Backend.md 12.1.1 / 12.1.3 / 18.1.1 第 7 步）。仅开发用，不入生产路径；
各服务 schema 内表结构由服务自带 Flyway 版本化迁移脚本建立（Backend.md 12.3.3）。

```bash
psql -h localhost -U $PG_USER -d $PG_DB -f backend/dev-init/00-platform-schemas.sql
```
