# 零 Docker 高速全栈测试架构重构（后端嵌入式中间件 + 双执行隔离 + 前端共享 jsdom 分片）

> 取代 `2026-11-12-03-flex-test-dialect-isolation.md` 中“整模块 `reuseForks=false`”的隔离实现（保留其“H2 切片与 PG 集成必须分 JVM 隔离 Flex 全局静态方言”的核心结论，改为 surefire 双 `<execution>` 仅 2 个 JVM）。
> 装配操作细则见 `.ai-memory/playbooks/flex-test-assembly.md`。

## 背景

随业务与平台模块增长，开发后全量测试耗时严重拖慢反馈闭环：
1. **后端全量 `mvn test` 实测 30~55 分钟**，且强依赖本机 Docker Desktop（含 Windows 命名管道/镜像拉取不稳定痛点）：
   - 87 个测试类各自以 `@Container PostgreSQLContainer` 独立启停 PG 容器，33 个类各自启停 Redis 容器，2 个类启停 Kafka 容器，1 个类（`MinioObjectStorageTest`）因 `docker.io` 不可达被迫 `@Disabled`；
   - `platform-system-service`、`platform-workflow-service`、`platform-scheduler-service` 三个模块配置 `<reuseForks>false</reuseForks>`，单次运行拉起 93 个独立 JVM，完全丧失 Spring `ApplicationContext` 缓存复用；
   - `platform-infra-service` 测试域未禁止 Spring Cloud Stream 自动探测 `auditEventIn` 消费函数，导致每个 Spring 上下文启动空转等待 `localhost:9092` 60 秒（11 个集成类合计白耗约 11 分钟）。
2. **前端全量 `CI=true pnpm test` 实测 215s~735s**（`@acme/web` 单包 197s~718s）：
   - 74 个 `.test.tsx` 组件测试在默认隔离模式下逐文件重复解析 `antd`（~2.5s）+ `@ant-design/icons`（~2.3s）；
   - `@ant-design/cssinjs` 在 jsdom 注入海量 `<style>` 节点，使 Testing Library `getByRole`/`findByRole` 样式计算退化为 $O(N \times M)$（单条删改用例 4~15s）。

## 决定

### 一、后端：零 Docker 进程内原生中间件 + 上下文复用 + 内存二进制快照

1. **零 Docker 嵌入式基础设施（仅 `test` 作用域，登记于 `acme-parent` 与 `common-test`）**：
   - **PostgreSQL 17**：引入 `io.zonky.test:embedded-postgres:2.2.3` + `embedded-postgres-binaries-bom:17.12.0`（与生产 PG 17 同版本），封装 `EmbeddedPostgresSupport`：
     - 跨 JVM 复用 `%TEMP%/acme-embedded-pg-bin-17.12` 原生二进制解压缓存（避免每模块重复解压 80MB），每 JVM 独立临时数据目录 + 随机空闲端口，完整支持 `mvn -T 4` 多模块并发；
     - 关闭测试写盘同步参数（`fsync=off`、`synchronous_commit=off`、`full_page_writes=off`、`max_connections=200`），保留 100% 真实 PG 17 语义（Flyway 全量迁移、`pgcrypto`、`jsonb`、部分唯一索引、RLS 策略与非超管角色）；
     - 首次 Flyway 迁移后通过 PostgreSQL 原生 `CopyManager`（`COPY ... TO STDOUT (FORMAT BINARY)`）将非空种子表与序列状态快照到 JVM 内存（零库内额外 schema、零 `information_schema` 污染）；类间通过 `SET LOCAL session_replication_role = 'replica'` + 实时枚举 `TRUNCATE ... RESTART IDENTITY CASCADE` + `COPY ... FROM STDIN (FORMAT BINARY)` + `setval` 在 ~5~20ms 内还原库态，不破坏共享 HikariCP 与 Spring 上下文。
   - **Redis**：引入 `com.github.codemonstur:embedded-redis:1.4.4`（排除传递 `jedis`），封装 `EmbeddedRedisSupport`（单 JVM 单例随机端口、禁用落盘、原生 RESP `FLUSHALL` <1ms 重置；支持 Lua `EVAL`、多字段 `HSET` 与 `PUB/SUB`）。
   - **Kafka**：`platform-workflow-service` 与 `acme-bootstrap` 以 `org.springframework.kafka:spring-kafka-test` 的 `EmbeddedKafkaKraftBroker`（随机端口进程内 KRaft broker）替代 Testcontainers Kafka 容器。
   - **MinIO/S3**：`framework-storage` 的 `MinioObjectStorageTest` 改用 JDK 内置 `HttpServer` 进程内 S3 桩，移除 `@Disabled`，真实覆盖 `MinioClient` HTTP 签名与 PUT/HEAD/GET/DELETE 协议栈。
2. **共享基座与隔离边界**：
   - `platform-system-service`、`platform-auth-service`、`platform-infra-service` 分别提供 `AbstractSystemIntegrationTest` / `AbstractAuthIntegrationTest` / `AbstractInfraIntegrationTest`，统一公共配置键与类间快照/Redis 重置；
   - 有破坏性 DDL（如 `DROP TABLE`）、自定义非超管连接、独立 WebSocket 端口、后台 `@Scheduled`/ShedLock 竞态或独立 Kafka broker 的测试类，在共享 PG 进程内按独立库名（`pg.jdbcUrl("acme_<isolated>", ...)`）隔离。
3. **Surefire 双执行替代整模块 `reuseForks=false`**：
   - `platform-system-service` 移除 `<reuseForks>false</reuseForks>`，拆为 `default-test`（排除 `**/*SliceTest.java`，`reuseForks=true`）与 `slice-tests`（仅跑 `**/*SliceTest.java`，`reuseForks=true`），将 81 个 JVM 压至 2 个 JVM，仍保持 100% JVM 级 Flex H2/PG 方言隔离；
   - `platform-workflow-service` 与 `platform-scheduler-service` 无 H2 切片，直接恢复默认 `reuseForks=true`。
4. **消除测试期 Kafka 占位空转**：
   - `platform-infra-service` 测试域配置 `spring.cloud.stream.function.autodetect: false` + `management.health.binders.enabled: false`，消除 60s/上下文的 `localhost:9092` 阻塞；
   - `platform-workflow-service` 测试域默认 `acme.stream.enabled: false`，仅 `WorkflowEventPublishIntegrationTest` 按类显式开启 `acme.stream.enabled=true` 与 `max.block.ms=15000`。

### 二、前端：`@acme/web` 持久化共享 jsdom + cssinjs 跳过 + 动态隔离路由 + 并发分片

1. **`tests/setup.ts`**：
   - jsdom 下跳过 `@ant-design/cssinjs` 的 `useStyleRegister` / `useCSSVarRegister` 运行时样式编译与 `<style>` 注入，并注入 4 条最小可见性规则（`#acme-test-antd-hidden`，覆盖 Popconfirm/Tooltip/Dropdown/Select 关闭态 `display:none`），保持 Testing Library 可见性语义不变；
   - 兼容 `window.getComputedStyle(elt, pseudoElt)` 与 `URL.revokeObjectURL`，并在 `afterAll` 重置定时器、全局桩、`useTabsStore` 与 `useStore`（含内部 `persisted` 闭包），杜绝跨文件状态泄漏。
2. **`tests/shared-jsdom-env.ts`**：同 worker 内复用单个 `JSDOM` 实例（仅在文件间清理 DOM、storage 与 `location.hash`），解决 Vitest `isolate: false` 销毁重建 `JSDOM` 导致 `react-dom` 持有脱离 `document` 的问题，使 `antd`/`@ant-design/icons` 每分片仅解析 1 次。
3. **`vitest.workspace.ts` + `scripts/run-tests.mjs`**：
   - 静态扫描含 `vi.mock(` / `vi.useFakeTimers(` / `setupServer(` 的 `.test.tsx` 与少数全局壳测试，自动路由至 `web-dom-isolated`（`isolate: true`）；`web-node`（24 个 `.test.ts`）保持 `isolate: true`；其余洁净 `.test.tsx` 轮询均分至 4 个 `singleFork` / `shared-jsdom` 分片；
   - `scripts/run-tests.mjs` 在无参全量运行时并发拉起 5 个 Vitest 进程（绕开 Vitest 2.x 全局 `singleFork` 串行限制），有参定向运行时直接透传 `vitest run`。

## 实测效果

| 范围 | 重构前 | 重构后（零 Docker） | 提速倍率 |
|---|---|---|---|
| 后端全仓 54 模块（2,017 用例，0 跳过） | 30~55 分钟（需 Docker，1 跳过） | **7m 13s**（`mvn -T 4 -B test`） | **~4.5x ~ 7.5x** |
| `platform-system-service`（502+ 用例） | ~15~20 分钟（81 JVM + 49 容器） | **2m 54s**（单跑）/ 3m 48s（4 并发） | **~5x ~ 7x** |
| `platform-infra-service` | ~12 分钟 | **1m 28s**（单跑）/ 2m 31s（4 并发） | **~8x** |
| `platform-auth-service`（202 用例） | ~5+ 分钟 | **~1m 35s**（单跑）/ 2m 20s（4 并发） | **~3.5x** |
| `platform-workflow-service`（74 用例） | ~6+ 分钟 | **1m 50s ~ 2m 20s** | **~3x** |
| 前端全量 `CI=true pnpm test` | ~215s ~ 735s | **87.8s** | **~2.5x ~ 8x** |
| `@acme/web`（98 文件） | 197s（空闲）~ 718s（负载） | **79.5s** | **~2.5x ~ 9x** |

## 被否的选项

- **后端全迁 H2 替代 PG**：否决——仓内 48 份 Flyway 迁移广泛使用 `pgcrypto`、`jsonb`、RLS 策略、`DO $$` 与部分唯一索引，H2 无法承载且违背 AC-013 实体-脚本一致性实证底线。
- **保留 Docker 并仅做 Testcontainers `withReuse(true)`**：否决——仍强依赖本机 Docker Daemon、镜像拉取与 Windows 命名管道稳定性，不符合“本地零 Docker 依赖”目标。
- **前端全量无条件 `isolate: false`**：否决——含 `vi.mock` / `vi.useFakeTimers` / `setupServer` 的文件会跨文件污染模块图与计时器；动态静态分类路由兼顾速度与零维护成本。
