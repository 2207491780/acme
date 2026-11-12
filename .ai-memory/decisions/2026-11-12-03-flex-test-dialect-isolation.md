# Flex 测试 JVM 全局方言污染：H2 切片与 PG 集成必须隔离

> 承接 `2026-11-12-02-flex-data-access-residuals.md` 决定 1（AC-013 由 PG 集成测试兜底，合并前必须在有 Docker 环境跑过一轮）。
> 本记录是该轮真库验证的发现与修复定稿，不取代 02。装配操作细节见 `.ai-memory/playbooks/flex-test-assembly.md`。

## 背景

启动 Docker 跑全量 `mvn test`（postgres:17-alpine 真库）后，`SystemIntegrationTest` 10 用例 7 错：
`BadSqlGrammarException: ERROR: syntax error at or near "`"`，生成 SQL 形如
`SELECT COUNT(*) AS \`total\` FROM \`sys_tenant\` WHERE \`deleted\` = ?`（MySQL 反引号）。
同轮 `InfraIntegrationTest` / `BusinessContentIntegrationTest` / `AuthSeedIntegrationTest` 三个 PG 集成全绿，
框架层 H2 切片（FlexDataAccessTest / FlexRepositorySliceTest 等）也全绿；单独跑 `SystemIntegrationTest`（不与 H2 切片同 JVM）也全绿。

根因（Flex 1.11.8 源码核实）：
1. `DialectFactory.createDialect` 中 `case H2` 与 `case MYSQL` 共用 `KeywordWrap.BACK_QUOTE`（反引号），PG 用 `DOUBLE_QUOTATION`；H2 自身容忍反引号故切片不暴露。
2. 查询期方言取自 JVM 级静态单例 `FlexGlobalConfig.getDefaultConfig().getDbType()`。
3. `FlexSqlSessionFactoryBuilder.initGlobalConfig` 仅在 `configUnInitialize || isDefault` 时刷新全局默认方言；两个 Spring 上下文共用同一 MyBatis environment id，第二次（PG）构建时该条件为 false，全局默认方言停留在先建的 H2 上下文。
4. surefire 缺省 `reuseForks=true`：platform-system-service 是唯一同模块混跑 H2 切片（FlexRepositorySliceTest）+ PG 集成（SystemIntegrationTest）者，二者共用一个 JVM → 全局方言被 H2 污染 → PG 生成反引号报错。infra/business/auth 无 H2 切片，单上下文 PG 探测正确故通过。

生产每服务单 PG 数据源、单 JVM 单上下文，不受影响——这是测试隔离缺陷，非生产代码缺陷。

## 决定

1. **platform-system-service 的 surefire 设 `reuseForks=false`**：每个测试类独立 JVM，隔离 Flex 全局静态方言。`FlexRepositorySupport`、方言自动探测、SlowSqlSupport 包装均正确，不改生产代码。
2. **口径推广**：凡同模块混跑 H2 切片 + PG（或其他异方言）集成，必须 `reuseForks=false`；已写入 playbook 装配口径，新服务照抄。
3. **AC-013 兜底本轮已履行**：4 个 PG 集成测试（System / BusinessContent / Infra / AuthSeed）在真库跑过；修复后全量绿。用户口径“5 个”实为 4 个 PG 真库集成（另有 Redis/Minio 容器测试，非 PG）。

## 被否的选项

- **注册 H2 双引号方言使污染无害**：否决——H2 方言同时用 MySQL `LIMIT offset,count` 语法，PG 分页仍非法；只改引号治标不治本。
- **PG 测试 `@BeforeAll` 硬设 `FlexGlobalConfig.getDefaultConfig().setDbType(POSTGRE_SQL)`**：否决——依赖 Flex 内部静态、脆弱，测试顺序或上下文刷新变化即失效，且把框架内部知识渗进服务测试。
- **集成测试改名 `*IT` 迁 failsafe（verify 阶段独立 JVM）**：否决——改变 `mvn test` 跑全部的现有契约（playbook 与后端 AGENTS.md 均约定 `mvn test`），跨模块 blast radius 大。
- **parent 级 surefire `reuseForks=false`**：否决——拖慢所有模块测试，当前仅 system 混跑；最小改动只改受影响模块，其余按需在 playbook 指引。