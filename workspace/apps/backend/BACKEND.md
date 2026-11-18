# backend — 后端应用

Java 25 + Spring Boot 4.1.1 多模块 Maven 后端工程，已落地平台服务与统一接入网关（业务域聚合壳保留，暂无子域）。

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

> **部署红线**：主配置中的口令均为 `${ENV_VAR:本地默认}` 占位形态（如 `PG_PASSWORD`、`NACOS_PASSWORD`、`REDIS_PASSWORD`），
> 本地默认值仅供 `env-stack/` 本地栈使用；**生产部署必须通过环境变量全部覆盖，禁止沿用任何默认值**。

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
│   ├── acme-bootstrap/     # 单体打包形态部署单元（REQ-20261118-002130：聚合三服务可选形态）
│   ├── acme-business/      # 业务域聚合（当前空壳，新增业务域按需追加）
│   └── acme-gateway/       # 统一接入网关（WebFlux）
├── AGENTS.md / CLAUDE.md   # 后端补充约定（.ai/ 派生）
└── BACKEND.md              # 本文件
```

`*-api` 为对外契约模块，`*-service` 为实现与部署单元；闲置框架模块保留为脚手架。

## 构建与测试

Maven 不在 PATH，用绝对路径调用（口径源见 [`.ai-memory/playbooks/flex-test-assembly.md`](../../../.ai-memory/playbooks/flex-test-assembly.md)「构建与运行」）；构建/测试在 `acme-cloud/` 下执行：

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
| acme-gateway | 9888 | `AcmeGatewayApplication` |
| acme-bootstrap（单体形态） | 9680 | `MonolithApplication` |

```bash
# 在 acme-cloud/ 下起单个服务（依赖环境就绪后）
"$MVN" -pl acme-platform/platform-system/platform-system-service spring-boot:run
```

## 迁移脚本目录归属与版本协调（REQ-20261118-002130 确认①）

三服务 Flyway 迁移各自收敛子目录 `src/main/resources/db/migration/{auth|system|infra}`
（单 JVM classpath 合流——单体形态——下同路径迁移会互相污染；checksum 与路径无关，挪目录
不破坏既有历史），各服务 main/test yml 以 `spring.flyway.locations` 显式声明自己的子目录。

- 版本号跨服务各自协调、无全局单调约束：platform-system 至 **V19**、platform-infra 至 **V18**、
  platform-auth 至 **V6**（当前现状）。
- 新迁移取号：多代理流程下由 TL 在委派单预分配（禁止实现者自行取号）；同一服务内版本号
  严格递增，跨服务允许同号（各自 schema 独立历史表 `flyway_schema_history`）。
- 单体形态下三 Flyway 实例由 `acme-bootstrap` 的 `MonolithFlywayConfig` 手动装配
  （`spring.flyway.enabled=false` 关闭 Boot 自动配置）：同一 DataSource、schemas/defaultSchema
  与 locations 各自独占，历史表分别落 `platform_auth/system/infra` 三 schema。

## 双形态：分布式（默认）与单体打包（可选）

分布式为默认形态（四服务 + 网关，Nacos/Kafka 全量）；单体为可选打包路径（本地联调 / 小部署），
新增 `acme-bootstrap` 聚合部署单元（启动类 `MonolithApplication`，端口 9680）。

```bash
# 构建单体可执行 jar（acme-cloud/ 下）
"$MVN" -pl acme-bootstrap -am package -DskipTests
# 本地启动（env 同分布式：PG_*/REDIS_* + 双 HMAC + 三种子密码；无 Nacos/Kafka）
PG_HOST=127.0.0.1 PG_PORT=5432 java -jar acme-bootstrap/target/acme-bootstrap-*.jar
```

单体装配要点（REQ-20261118-002130 确认②③④，细节见各类 javadoc）：

- **Feign 本地短路**：`spring.cloud.openfeign.lazy-attributes-resolution=true` + 7 个 `@Primary`
  本地适配器（`com.acme.bootstrap.local.Local*Client`，直调本域 UseCase，javadoc 注明「单体本地
  短路；分布式由 Feign 提供」）；Feign 工厂 bean 定义惰性 + primary 降级
  （`MonolithApplication.MonolithContextDefinitionCustomizer`，@FeignClient primary 缺省 true 与
  本地适配器冲突，并顺带移除审计 MQ 消费函数 `auditEventIn` 的定义——SCSt 在空 function.definition
  下会自动绑定全部函数）+ `spring.main.allow-bean-definition-overriding=true` 消解
  AuthorizationClient 被 auth/infra 双声明产生的同名 FeignClientSpecification——Feign 代理
  永不实例化、无 HTTP 出站。
- **中间件合并**：单 DataSource（search_path 覆盖三 schema，表名无交集）+ 手动三 Flyway 实例 +
  Redis 一份（键前缀天然隔离）+ Nacos 软依赖关闭 + stream 关闭（无 Kafka 出站）。
- **审计本地 sink**：`LocalAuditEventSink`（@Primary AuditEventSink）直调 `AuditIngestUseCase`，
  事件直落 `acme_op_audit`（去重门 M-12 语义保留，无 MQ 回环）。
- **边缘认证**：`BootstrapAuthFilter`（order=+150，先于 InternalTokenAuthFilter 的 +200）承接
  网关用户令牌面——验签（`UserTokenAccessVerifier`，framework-security 抽取载体，与网关
  `DefaultUserTokenVerifier` 委托同源）→ 撤销检查（同 JVM 直调 `SessionRedis`，FAIL_OPEN）→
  `JwsInternalTokenIssuer` 签内部令牌注入 `X-Acme-Token` 放行上下文绑定。
- **迁移**：三 Flyway 实例（见上节）；`acme.flex.id.worker-id=1`（单 JVM 一份雪花单例）。

能力差异（单体 vs 网关形态）：

| 能力 | 网关形态 | 单体形态 |
|---|---|---|
| 用户令牌验签/注入 | gateway 过滤链（AuthFilter/InjectIdentityFilter） | BootstrapAuthFilter 同语义 |
| 撤销检查 | RevocationCheckFilter（FAIL_OPEN） | 同 JVM 直调 SessionRedis，FAIL_OPEN 同口径 |
| 限流 / XSS 清洗 / 灰度 / 伪造头剥离 | gateway 承载 | 不携带（网关职责；`StripSpoofedHeadersFilter` 依赖项消失，但内部上下文只从内部令牌 claims 绑定，无伪造面） |
| /docs 聚合文档 | springdoc webflux 聚合 | 缺失（各服务本无 springdoc） |
| 401 信封错误码段 | SYS-03 网关段 | SYS-02 服务侧段（HTTP 状态一致） |
| lastActiveAt 逐请求刷新 | 网关每请求 HSET | 仅登录/续约/刷新时点 |
| Nacos / Kafka | 必需 | 软依赖关闭（可完全离线） |

镜像与部署：`Dockerfile` `PACKAGE=bootstrap` 形态标记 + cd.yml `package_mode` 输入
（monolith 构建 `acme-bootstrap→acme/acme-monolith` 镜像、跳过四服务）；env-stack
`overlays/monolith`（交付不部署）——见 [`ENV-STACK.md`](../../env-stack/ENV-STACK.md)「双形态」。

## 依赖与环境

- 外部依赖：Nacos（配置/注册）、PostgreSQL（主库）、Redis（缓存/令牌撤销）、Kafka（审计事件，经 framework-stream）、Minio（对象存储）。
- 真实凭证不写入本目录；环境**搭建**见 [`ENV-STACK.md`](../../env-stack/ENV-STACK.md)，**连接**（MCP）见 [`.ai/env/ENV-GUIDE.md`](../../../.ai/env/ENV-GUIDE.md)。