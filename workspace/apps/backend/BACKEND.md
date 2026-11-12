# backend — 后端应用

Java 25 + Spring Boot 4.1.1 多模块 Maven 后端工程，已落地平台服务、业务域与统一接入网关（非骨架）。

## 职责

存放后端服务源码、应用级测试与构建配置。数据库/中间件/环境编排统一在 [`workspace/env-stack/`](../../env-stack/ENV-STACK.md)；AI 规范唯一事实源在根 [`.ai/`](../../../.ai/RULES.md)，后端补充约定见同目录 [`AGENTS.md`](AGENTS.md)（由 `.ai/` 派生，勿手改）。

## 技术栈

| 项 | 版本 / 选型 |
|---|---|
| 语言 | Java 25 |
| 框架 | Spring Boot 4.1.1 / Spring Cloud 2025.1.3 |
| 数据访问 | MyBatis-Flex 1.11.8（PostgreSQL） |
| 序列化 | Jackson 3（`tools.jackson`） |
| 网关 | Spring WebFlux（禁 Servlet） |
| 构建 | Maven 多模块，`revision=1.0-SNAPSHOT` |

## 目录结构

```text
backend/
├── acme-parent/            # 依赖/插件父 POM（版本、编译、enforcer 禁依赖）
├── acme-cloud/             # 聚合工程（在此构建/测试）
│   ├── acme-common/        # 公共池 8：context contract core i18n serialization test util validation
│   ├── acme-framework/     # 框架层 22：audit cache core doc excel feign gateway i18n idempotent
│   │                       #   jackson job lock mybatis-flex observability ratelimit security
│   │                       #   storage stream tenant trace tx web
│   ├── acme-platform/      # 平台服务（各 api + service + Dockerfile）
│   │   ├── platform-auth/      # 认证授权
│   │   ├── platform-system/    # 租户 / 用户 / 角色权限
│   │   └── platform-infra/     # 基础设施（审计 / 存储 / 消息）
│   ├── acme-business/      # 业务域
│   │   └── business-content/   # 内容（api + service + Dockerfile）
│   └── acme-gateway/       # 统一接入网关（WebFlux）
├── AGENTS.md / CLAUDE.md   # 后端补充约定（.ai/ 派生）
└── BACKEND.md              # 本文件
```

`*-api` 为对外契约模块，`*-service` 为实现与部署单元；闲置框架模块保留为脚手架。

## 构建与测试

Maven 不在 PATH，用绝对路径调用；构建/测试在 `acme-cloud/` 下执行：

```bash
cd workspace/apps/backend/acme-cloud
MVN="C:/Program Files/Maven/apache-maven-my/bin/mvn.cmd"
"$MVN" test                     # 全量测试
"$MVN" -pl <module> -am test    # 单模块（含上游依赖）
"$MVN" -DskipTests package      # 打包
```

测试三层（装配口径见 [`.ai-memory/playbooks/flex-test-assembly.md`](../../../.ai-memory/playbooks/flex-test-assembly.md)）：

- **单元**：纯 JUnit，无 Spring 上下文。
- **切片**：内嵌 H2，`@SpringBootTest` 不配 `spring.datasource.url`。
- **集成**：Testcontainers（`postgres:17-alpine` / `redis:7.4-alpine` / `minio/minio`），`@Testcontainers(disabledWithoutDocker=true)`。

注意：

- 无 Docker 时集成测试**自动跳过——跳过不等于验证**，合并前必须在有 Docker 环境实跑。
- Flex 方言为 JVM 静态全局，H2 切片与 PG 集成同模块共存需 surefire `reuseForks=false`（已在相关模块配置）。

## 服务与启动

各服务为 Spring Boot 应用，配置经 Nacos 导入（`spring.config.import: nacos:<name>.yaml`）；启动前需先按 env-stack 起依赖环境。

| 服务 | 端口 | 启动类 |
|---|---|---|
| platform-infra | 9880 | `PlatformInfraApplication` |
| platform-system | 9881 | `PlatformSystemApplication` |
| platform-auth | 9882 | `PlatformAuthApplication` |
| business-content | 9883 | `BusinessContentApplication` |
| acme-gateway | 9888 | `AcmeGatewayApplication` |

```bash
# 在 acme-cloud/ 下起单个服务（依赖环境就绪后）
"$MVN" -pl acme-platform/platform-system/platform-system-service spring-boot:run
```

## 依赖与环境

- 外部依赖：Nacos（配置/注册）、PostgreSQL（主库）、Redis（缓存/令牌撤销）、Kafka（审计事件，经 framework-stream）、Minio（对象存储）。
- 真实凭证不写入本目录；环境**搭建**见 [`ENV-STACK.md`](../../env-stack/ENV-STACK.md)，**连接**（MCP）见 [`.ai/env/ENV-GUIDE.md`](../../../.ai/env/ENV-GUIDE.md)。