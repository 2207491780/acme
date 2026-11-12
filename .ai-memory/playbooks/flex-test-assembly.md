# 后端测试装配口径（MyBatis-Flex）

> 后端新增服务模块或编写仓储/集成测试前必读。触发：`workspace/apps/backend` 分层规则；取舍背景见
> `.ai-memory/decisions/2026-11-12-02-flex-data-access-residuals.md` 与
> `.ai-memory/decisions/2026-11-12-03-flex-test-dialect-isolation.md`。

## 三层测试环境

| 层 | 环境 | 何时用 |
|---|---|---|
| 单元 | 纯 JUnit（无 Spring） | 用例/领域逻辑，直接构造被测类 |
| 切片 | 内嵌 H2 + 真实 mapper SQL 面 | 仓储/框架切片（租户过滤、审计、软删、乐观锁） |
| 集成 | Testcontainers（PG / Redis） | 服务级闭环：Flyway 真库、种子幂等、跨用例链路 |

## 装配口径（踩坑点，新服务照抄）

1. **`@SpringBootTest` 不设 `spring.datasource.url`**：切片测试靠内嵌 H2 自动装配（DataSource 兜底链）；显式设 URL 而无 HikariCP 反而失去 DataSource。测试域 `src/test/resources/application.yml` 遮蔽主配置（关 Nacos，`spring.config.import=` 置空）。
2. **切片表结构**：`spring.flyway.enabled=false` + `src/test/resources/schema.sql`（列定义对齐 `V1__init_schema.sql`）；共享上下文共享同一 H2 库，每用例 `@BeforeEach` 用 `JdbcTemplate` 物理清表（jdbc 直连不经 mapper 过滤面）。
3. **mapper 注册**：服务主应用（`*Application`）**无需 `@MapperScan`**（starter 按 AutoConfigurationPackages 注册主包）；**独立包测试应用**（`com.acme.framework.*.app` 等不在服务包下）必须显式 `@MapperScan` 本包。
4. **绑定表物理删除**：继承 AuditBase（带 deleted 列）但语义要求物理删的表（如 sys_user_role，软删行占唯一键致重绑冲突），删除统一走 `FlexDeletes`（`forceDeleteByQuery` / `execWithoutLogicDelete` 旁路），不要直接 `mapper.deleteByQuery`。
5. **计数**：`selectCountByQuery` 用独立无排序 QueryWrapper（不剥离 ORDER BY）；分页直接 `paginate`（内部处理 count）。
6. **PG 集成测试**：`@Testcontainers(disabledWithoutDocker = true)` + `PostgreSQLContainer("postgres:17-alpine")` + `@DynamicPropertySource` 注入 datasource url 与 `spring.flyway.default-schema/schemas=public`。无 Docker 自动跳过≠验证过：合并/发布前必须在有 Docker 环境跑一轮（AC-013 实体-脚本一致性唯一兜底）。
7. **Redis 容器**：`GenericContainer("redis:7.4-alpine").withExposedPorts(6379)`，Lettuce 连接工厂指向容器映射端口。
8. **同模块混跑 H2 切片 + PG 集成必须 `reuseForks=false`**：Flex 全局默认方言是 JVM 级静态（`FlexGlobalConfig.defaultConfig`），H2 与 MySQL 共用反引号方言；surefire 缺省 `reuseForks=true` 时，先建的 H2 上下文会把全局方言污染成反引号，后建的 PG 上下文不刷新（共用 environment id），致 PG 生成 `` `col` `` 报 `syntax error at or near "`"`。在该服务 pom 的 surefire 配 `<reuseForks>false</reuseForks>`（每测试类独立 JVM）。仅混跑异方言的模块需要；纯 PG 或纯 H2 模块不必。参考 `platform-system-service/pom.xml`。

## 参考实现（照抄这些类，不要另起炉灶）

- 框架域 H2 切片：framework-mybatis-flex `FlexDataAccessTest` / `FlexPhysicalDeleteTest` + `app/FlexTestApplication`
- 租户过滤 H2 切片：framework-tenant `TenantFilterFlexTest` + `app/TenantFlexTestApplication`
- 服务域 H2 切片：platform-system `FlexRepositorySliceTest` + `src/test/resources/{application.yml,schema.sql}`
- PG 集成：`SystemIntegrationTest`（Flyway + 种子幂等 + 租户隔离 + 六能力闭环）；business-content / auth-seed / infra 同型
- Redis 集成：framework-idempotent `IdempotentRedisIntegrationTest`；platform-auth `AuthRedisSupportIntegrationTest`

## 构建与运行

- Maven 不在 PATH：`"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd"`；JDK 25（JAVA_HOME 已设）。
- 全量测试：`workspace/apps/backend/acme-cloud/` 下 `mvn test`（容器镜像 postgres:17-alpine / redis:7.4-alpine 需本地可拉取或已缓存）。
- 单模块快速验证：`mvn -pl <模块相对路径> test`（依赖已安装时免 `-am`）。
