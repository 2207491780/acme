# backend 目录规范

本目录是后端开发根目录（Maven 多模块，Java 25 / Spring Boot 4.1.1 / Spring Cloud 2025.1.3）。
**权威规范是 `doc/Backend.md`**（18 章全覆盖：架构、分层、构建、契约、事务、网关等）；本文件只列高频要点，冲突时以 Backend.md 为准。

## 构建（要点；完整命令见 `.ai-memory/playbooks/dev-commands.md`）

- 冷启动先 `mvn -N install -f acme-parent/pom.xml`，再 `mvn -f acme-cloud/pom.xml clean install`。
- 增量：`mvn -pl <module> -am -f acme-cloud/pom.xml install`（Backend.md 6.4.2）。
- ❌ 不得在子模块目录内单独 `install` 作为交付手段；不手改 `.flattened-pom.xml`（构建生成物）。

## 模块职责（Backend.md 5.2 / 7-10）

| 模块 | 职责 |
|---|---|
| `acme-common` | 通用契约与工具，依赖最轻，变更需兼容（7.2） |
| `acme-framework` | 技术能力封装（web/缓存/消息等），不含业务（8） |
| `acme-gateway` | 网关：路由、鉴权、限流（11） |
| `acme-platform` | 平台服务（auth / system 等） |
| `acme-business` | 业务服务，按业务域拆分 |

- 依赖方向遵循 4.4，禁止反向依赖与跨服务共享表。

## 数据与初始化

- 表结构由各服务自带 **Flyway** 版本化迁移建立（Backend.md 12.3.3）。
- `dev-init/` 下的 SQL 仅用于本地首次建 schema 与账号（开发专用，不入生产路径）。

## 环境

- 本地依赖栈：`docker compose -f workspace/env-stack/docker/dev/compose.dev.yml up -d`（组件与端口见 env-stack）。
- MCP 连接定义：`.ai/env/dev.yaml`（dev 可读写；prod 只读）。
