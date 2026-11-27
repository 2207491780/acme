# 后端测试装配口径（MyBatis-Flex + 零 Docker 嵌入式底座）

> 后端新增服务模块或编写仓储/集成测试前必读。触发：`workspace/apps/backend` 分层规则；取舍背景见
> `.ai-memory/decisions/2026-11-12-02-flex-data-access-residuals.md`、
> `.ai-memory/decisions/2026-11-12-03-flex-test-dialect-isolation.md` 与
> `.ai-memory/decisions/2026-11-27-01-zero-docker-fast-test-architecture.md`。

## 三层测试环境（全部零 Docker 本地直跑）

| 层 | 环境 | 何时用 |
|---|---|---|
| 单元 | 纯 JUnit（无 Spring） | 用例/领域逻辑，直接构造被测类 |
| 切片 | 内嵌 H2 + 真实 mapper SQL 面 | 仓储/框架切片（租户过滤、审计、软删、乐观锁），命名须以 `*SliceTest` 结尾 |
| 集成 | 零 Docker 嵌入式 PG 17 / Redis / Kafka KRaft | 服务级闭环：Flyway 真库、RLS/非超管、种子幂等、Redis Lua/PubSub、事件流 |

## 装配口径（踩坑点，新服务照抄）

1. **`@SpringBootTest` 不设 `spring.datasource.url`（仅 H2 切片）**：切片测试靠内嵌 H2 自动装配（DataSource 兜底链）；显式设 URL 而无 HikariCP 反而失去 DataSource。测试域 `src/test/resources/application.yml` 遮蔽主配置（关 Nacos，`spring.config.import=` 置空）。
2. **切片表结构**：`spring.flyway.enabled=false` + `src/test/resources/schema.sql`（列定义对齐 `V1__init_schema.sql`）；共享上下文共享同一 H2 库，每用例 `@BeforeEach` 用 `JdbcTemplate` 物理清表（jdbc 直连不经 mapper 过滤面）。
3. **mapper 注册**：服务主应用（`*Application`）**无需 `@MapperScan`**（starter 按 AutoConfigurationPackages 注册主包）；**独立包测试应用**（`com.acme.framework.*.app` 等不在服务包下）必须显式 `@MapperScan` 本包。
4. **绑定表物理删除**：继承 AuditBase（带 deleted 列）但语义要求物理删的表（如 sys_user_role，软删行占唯一键致重绑冲突），删除统一走 `FlexDeletes`（`forceDeleteByQuery` / `execWithoutLogicDelete` 旁路），不要直接 `mapper.deleteByQuery`。
5. **计数**：`selectCountByQuery` 用独立无排序 QueryWrapper（不剥离 ORDER BY）；分页直接 `paginate`（内部处理 count）。
6. **PG 集成测试（零 Docker 共享底座）**：
   - 标准服务集成测试**直接继承模块公共基座**（`AbstractSystemIntegrationTest` / `AbstractAuthIntegrationTest` / `AbstractInfraIntegrationTest`），不再在测试类内声明 `@Testcontainers` / `PostgreSQLContainer`；公共基座共享单 JVM 原生嵌入式 PG 17（`EmbeddedPostgresSupport.shared()`）与嵌入式 Redis（`EmbeddedRedisSupport.shared()`），类间经 PostgreSQL 二进制 `COPY` 内存快照在 ~15ms 内还原库态（零 `information_schema` 污染）并执行 Redis `FLUSHALL`；
   - 需要额外属性覆盖时仅加 `@TestPropertySource(properties = ...)` 或子类 `@DynamicPropertySource`；
   - 有破坏性 DDL（如 `DROP TABLE`）、非超管角色连接、独立 WebSocket 端口、后台 `@Scheduled`/ShedLock 竞态或 Kafka broker 的测试类，使用 `EmbeddedPostgresSupport.shared().jdbcUrl("acme_<isolated_db>", "public")` 独占隔离库，避免污染共享库。
7. **Redis / Kafka / S3 集成测试（零 Docker）**：
   - Redis 集成使用 `EmbeddedRedisSupport.shared()`（随机端口原生 Redis，支持 Lua `EVAL`、多字段 `HSET`、`PUB/SUB`；注意 Windows 下 Redis 5.0.14 `INFO` 含反斜杠路径会导致 Spring Data Redis `DataRedisHealthIndicator` `Properties.load` 报 `\uxxxx`，单体健康检查测试域关闭 `management.health.redis.enabled: false`）；
   - Kafka 事件流集成使用 `spring-kafka-test` 的 `EmbeddedKafkaKraftBroker`（随机端口进程内 KRaft broker，`@AfterAll` 调用 `destroy()`）；非 Kafka 服务测试域（如 `platform-infra-service`）若存在 `@Bean Consumer<...>` 须配置 `spring.cloud.stream.function.autodetect: false` 与 `management.health.binders.enabled: false`，防止启动空转等待 `localhost:9092` 60 秒；
   - MinIO/S3 协议测试使用进程内 JDK `HttpServer` S3 桩（见 `MinioObjectStorageTest`），不依赖外部镜像拉取。
8. **同模块混跑 H2 切片 + PG 集成采用 surefire 双 `<execution>` 隔离（替代旧 `reuseForks=false`）**：Flex 全局默认方言是 JVM 级静态（`FlexGlobalConfig.defaultConfig`），H2 与 MySQL 共用反引号方言；在混跑异方言模块的 `pom.xml` 中配置双 execution：`default-test` 排除 `**/*SliceTest.java`（`reuseForks=true`）、`slice-tests` 仅包含 `**/*SliceTest.java`（`reuseForks=true`）。2 个 JVM 即可 100% 隔离方言并保留 Spring 上下文复用。参考 `platform-system-service/pom.xml`。

## 参考实现（照抄这些类，不要另起炉灶）

- 零 Docker 基础设施：`common-test` `EmbeddedPostgresSupport`、`EmbeddedRedisSupport`
- 框架域 H2 切片：`framework-mybatis-flex` `FlexDataAccessTest` / `FlexPhysicalDeleteTest` + `app/FlexTestApplication`
- 租户过滤 H2 切片：`framework-tenant` `TenantFilterFlexTest` + `app/TenantFlexTestApplication`
- 服务域 H2 切片：`platform-system` `FlexRepositorySliceTest` + `src/test/resources/{application.yml,schema.sql}`
- PG+Redis 共享基座集成：`AbstractSystemIntegrationTest` + `SystemIntegrationTest`；`AbstractAuthIntegrationTest`；`AbstractInfraIntegrationTest`
- 独立库/非超管/Kafka/S3 集成：`REQ202611251737RlsNonSuperuserIntegrationTest`、`REQ20261122233425WorkflowEventPublishIntegrationTest`、`REQ20261122233425LeaveE2eIntegrationTest`、`MinioObjectStorageTest`
- Redis 集成：`framework-idempotent` `IdempotentRedisIntegrationTest`、`framework-ratelimit` `RedisTokenBucketTest`、`platform-auth` `AuthRedisSupportIntegrationTest`

## 构建与运行

> 本节是 Maven 路径的唯一口径源（审查 2026-11-14 收敛：散文引用一律指向此处；子代理委派单与可执行脚本块因自包含原则例外保留字面量）。

- Maven 不在 PATH：`"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd"`；JDK 25（JAVA_HOME 已设）。
- **零 Docker 全量测试**：`workspace/apps/backend/acme-cloud/` 下推荐 `mvn -T 4 -B test`（54 模块 2,017 用例，**零 Docker 依赖、0 跳过**，实测约 **7 分钟**；串行 `mvn -B test` 亦可）。首次运行会在 `%TEMP%/acme-embedded-pg-bin-17.12` 解压缓存 PG 17 原生二进制，后续跨模块/跨次构建直接复用。
- 单模块快速验证：`mvn -pl <模块相对路径> -B test`（依赖已安装时免 `-am`；若跨分支切换后 API 变更，先 `mvn -T 1C -B install -DskipTests` 刷新本地 `.m2`，约 30 秒）。
- 定向用例：Surefire 3.5.4 须 `-Dsurefire.failIfNoSpecifiedTests=false`（`-DfailIfNoTests=false` 无效，多模块 `-am` 会在无匹配模块直接 BUILD FAILURE）。
- 判定口径：日志须见 `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`。
- 并发建议：全量后端与全量前端（`CI=true pnpm test`，实测约 **88s**）宜串行先后执行，避免 16 核机器下大量 JVM 与 Node 进程争抢 CPU 放大超时波动；前端定向单文件复跑可直接 `pnpm --filter @acme/web test <file>`（`scripts/run-tests.mjs` 自动透传参数给 `vitest run`）。
