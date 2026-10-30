# dev — 开发依赖环境（Docker Desktop）

本目录承载本地开发所需的中间件依赖，**仅开发用**，非生产编排（生产见 Backend.md 第 15 章）。

## 组件清单

| 目录 | 组件 | 端口（仅 127.0.0.1） | 说明 |
|---|---|---|---|
| [pgsql/](pgsql/) | PostgreSQL 17 | `${PG_PORT}`（5432） | 各服务独占库/schema（12.1.1） |
| [redis/](redis/) | Redis 7.4 | `${REDIS_PORT}`（6379） | 缓存 / 锁 / 限流（12.3.2 key 前缀） |
| [nacos/](nacos/) | Nacos v3.0.0 | `${NACOS_PORT}`（8848）+ `${NACOS_GRPC_PORT}`（9848）+ `${NACOS_CONSOLE_PORT}`（8080） | 注册 + 配置中心（14.3.1）；3.x 控制台在 8080 |
| [kafka/](kafka/) | Kafka 4.0（KRaft 单节点） | `${KAFKA_PORT}`（9092） | 消息（13.2 约束） |
| [rocketmq/](rocketmq/) | RocketMQ 5.3.1（nameserver + broker） | 9876 + 10911 + 10909 | 消息（13.2 约束）；poc-gate POC-3 多 binder 验证前置，仅开发用 |

## 使用

```bash
# 首次：创建本地环境变量（.env 不入库，凭证不硬编码）
cp env-stack/docker/dev/.env.example env-stack/docker/dev/.env   # 按需改端口/密码

# 启动 / 查看 / 停止（仓库根执行）
docker compose -f env-stack/docker/dev/compose.dev.yml up -d
docker compose -f env-stack/docker/dev/compose.dev.yml ps
docker compose -f env-stack/docker/dev/compose.dev.yml logs -f <组件名>
docker compose -f env-stack/docker/dev/compose.dev.yml down        # 停止（数据卷保留）
docker compose -f env-stack/docker/dev/compose.dev.yml down -v     # 停止并清数据（慎用）
```

启动就绪判断：`ps` 中各组件 `healthy`（healthcheck 已配）。单组件启停：`up -d pgsql`、`stop redis` 等。

## AI 接入（MCP）

本目录只负责**搭**；MCP **连**的唯一事实源是 [.ai/env/dev.yaml](../../../../.ai/env/dev.yaml)（Schema 与纪律见 .ai/env/ENV-GUIDE.md）。AI 需要操作中间件时按该文件建立连接，凭证一律用 `${VAR}` 引用、由启动器从本目录 `.env` 注入。

| MCP server | 组件 | 能力 |
|---|---|---|
| `pgsql` | PostgreSQL | SQL / 模式 / 数据（读写） |
| `redis` | Redis | 全数据类型键值（读写） |
| `kafka` | Kafka | 主题 / 消息 / 消费组（读写） |
| `nacos` | Nacos | 配置 / 服务 / 命名空间（读写） |

缺口（待补）：

- **Nacos MCP 启动器**：3.x 管理 API 需登录态与 `NACOS_AUTH_TOKEN`，无通用 MCP 包，需本地适配层（3.x 就绪探针与 v3 admin 路径与 2.x 不同）；补齐后在 [.ai/env/dev.yaml](../../../../.ai/env/dev.yaml) 登记 `command`。
- **RocketMQ 无 MCP server**：经 `docker compose exec` 或 `mqadmin` 操作（见 `rocketmq/ROCKETMQ.md`「中间件侧观测」）。

容器编排本身（启停、日志）不走 MCP，AI 直接执行本文「使用」一节的 docker compose 命令。

## 目录结构与约定

```text
docker/dev/
├── compose.dev.yml        # 编排入口：只做 include 聚合，不写组件细节
├── .env.example           # 环境变量模板（入库）
├── .env                   # 本地环境变量（不入库）
├── DEV-STACK.md
├── pgsql/                 # 一组件一目录：docker-compose.yml + 配置 + 组件说明文件
├── redis/
├── nacos/
├── kafka/
└── rocketmq/
```

- **网络**：所有组件接入 `acme-dev-stack` 网络，容器间以服务名互访（如 `kafka:29092`、`nacos:8848`）。
- **端口**：一律绑定 `127.0.0.1`；宿主机侧应用（IDE 启动）经映射端口连接。
- **凭证**：密码只放 `.env`，compose 用 `:?` 强制校验缺失即报错；配置文件不含密钥。
- **数据**：命名卷（`acme-*`），不落仓库；镜像钉版本，不用 `latest`（M-13 精神）。
- **健康检查**：每个组件必配 healthcheck，供 `ps` 与依赖方判断就绪。

## 如何新增组件（友好扩展）

1. 新建 `docker/dev/<组件名>/` 目录，放入三个文件：
   - `docker-compose.yml` —— 只写该组件的 `services` / `volumes` / `networks`（网络声明照抄任一组件的 `dev-stack` 段）；
   - 组件配置文件（无可省略）；
   - `<COMPONENT>.md` —— 用途、端口、连接示例、相关契约条款号。
2. 在 [compose.dev.yml](compose.dev.yml) 的 `include:` 列表追加一行 `<组件名>/docker-compose.yml`。
3. 在 [.env.example](.env.example) 追加该组件的端口/密码变量，并同步本地 `.env`。
4. `docker compose -f env-stack/docker/dev/compose.dev.yml up -d <组件名>` 验证 `healthy`。

可选组件（不默认启动）：在其 `docker-compose.yml` 的服务上加 `profiles: [<组件名>]`，仅当 `docker compose --profile <组件名> up -d` 时才拉起。

## 镜像源受限时（本地拉不动 Docker Hub）

compose 中镜像名保持官方名（可移植）；本机网络受限时经可用镜像源拉取后打回官方标签，编排无需改动：

```bash
docker pull <镜像源>/<官方镜像名>:<tag>
docker tag <镜像源>/<官方镜像名>:<tag> <官方镜像名>:<tag>
# 例：docker pull docker.m.daocloud.io/apache/kafka:4.0.0
#     docker tag docker.m.daocloud.io/apache/kafka:4.0.0 apache/kafka:4.0.0
```

也可在 Docker Desktop → Docker Engine 配置 `registry-mirrors` 全局加速。
