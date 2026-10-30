# pgsql — PostgreSQL

- 镜像：`postgres:17-alpine`；端口 `${PG_PORT}`（默认 5432，仅 127.0.0.1）
- 账号/库：`env-stack/docker/dev/.env` 的 `PG_USER` / `PG_PASSWORD` / `PG_DB`（不入库）
- 初始化：[init/00-init.sql](init/00-init.sql) 仅在数据卷首次初始化时执行
- 数据独占：每服务独占库或独立 schema（Backend.md 12.1.1），新增服务按 18.1.1 第 7 步建库；禁共享表（M-03）、禁跨库 JOIN（M-04）
- 变更管理：Flyway 版本化迁移脚本（12.3.3），生产 `ddl-auto=validate`

```bash
docker compose -f env-stack/docker/dev/compose.dev.yml exec pgsql psql -U acme -d acme
```

连接给宿主机侧应用（IDE 启动）：`jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}`
