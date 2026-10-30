# nacos — 注册 / 配置中心

- 镜像：`nacos/nacos-server`（版本由 `NACOS_VERSION` 控制，默认 v3.0.0）
- 端口：`${NACOS_PORT}`（8848，服务 API）；**`${NACOS_GRPC_PORT}`（9848 = 主端口 + 1000）必须映射**，Nacos 2.x/3.x 客户端依赖；`${NACOS_CONSOLE_PORT}`（8080，3.x 控制台独立端口）
- 模式：standalone + 内置存储；数据/日志用命名卷
- 鉴权：本地开启（`NACOS_AUTH_ENABLE=true`）；**3.x 镜像强制要求 `NACOS_AUTH_TOKEN`（Base64）与身份标识**，取值在 `.env`。3.x **不建默认用户**，本地管理员经 [init-admin/](init-admin/) 初始化，账号/口令在 `.env` 的 `NACOS_USERNAME`/`NACOS_PASSWORD`
- 配置治理：配置集中于配置中心、按命名空间隔离环境（Backend.md 14.3.1）；本地仅保留启动级配置
- 控制台：http://127.0.0.1:8080/（Nacos 3.x 起 8848 不再承载控制台；登录账号见 `.env`，仅本地）
- 就绪探针：`http://127.0.0.1:8080/v3/console/health/readiness`（v1 探针已废弃返回 410）

```bash
docker compose -f env-stack/docker/dev/compose.dev.yml logs -f nacos
# 宿主机侧应用连接（3.x 客户端同时依赖 8848 与 9848）：
#   server-addr = 127.0.0.1:${NACOS_PORT}
```
