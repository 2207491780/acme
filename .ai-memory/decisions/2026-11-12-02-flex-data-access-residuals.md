# MyBatis-Flex 数据访问迁移遗留口径定稿

> 迁移收尾六项遗留逐一定稿。测试装配操作细节见 `.ai-memory/playbooks/flex-test-assembly.md`。

## 背景

后端数据访问层自 Hibernate 迁移至 MyBatis-Flex（framework-mybatis-flex + framework-tenant + framework-web）后，六项口径未定稿：①ddl-auto=validate 无等效物（AC-013 实体-脚本一致性兜底缺失）；②乐观锁错误码占位与编号；③软删除审计语义；④TenantWriteGuard 异常被 MyBatis 包装；⑤计数与排序剥离；⑥测试装配口径。迁移会话将六项作为遗留交接，本记录定稿 ①~⑤，⑥ 见 playbook。

## 决定

1. **AC-013 兜底改为 PG 集成测试**：Flex 无 schema 校验能力，不引入框架级校验；实体-脚本一致性由 Testcontainers PostgreSQL 集成测试兜底（启动即 Flyway 真实 DDL 建表 + 全字段 CRUD + 种子幂等）。`@Testcontainers(disabledWithoutDocker = true)` 无 Docker 自动跳过——跳过≠验证，合并/发布前必须在有 Docker 环境跑过一轮（4 个 PG 集成测试：business-content / auth-seed / infra / system）。
2. **SYS-04-0002 `FlexError.STALE_WRITE`（409）**：仅仓储 `save` 按实体乐观锁更新 0 行时抛出（持有过期版本实体直接 save 的并发窗口）；常规并发 409 仍走 `WebError.CONCURRENCY_CONFLICT`（SYS-01-0003，含 Spring `OptimisticLockingFailureException` 映射）。SYS-04-0001 槽位随 BatchWriter 移除释放，框架错误码段自 0002 起编；zh/en 键集由 `FrameworkErrorI18nTest` 锁定（一一对应、无孤儿键）。
3. **软删除语义锚定为单列原子 UPDATE**：`AuditBase.deleted` 逻辑删除不递增 `version`、不刷新 `updated_at/by`（无 UpdateListener 参与）——与原 Hibernate 单列 update 口径一致，与常规实体更新路径不同（审计刷新仅存在于 update 路径）。由 `FlexDataAccessTest.softDeleteKeepsVersionAndAuditColumnsUntouched` 锚定。
4. **包装异常在 web 层按因果链还原**：缺租户写入拒绝（TENANT_REQUIRED）的 BizException 必须留在 InsertListener（数据操作面唯一全路径拦截点），接受 MyBatis 包装为 MyBatisSystemException（UncategorizedDataAccessException 子类、root cause 保留错误码）；`GlobalExceptionTranslator` 新增 `@ExceptionHandler(DataAccessException.class)` 沿因果链还原首个 BizException 走业务语义（403 + 原错误码），非业务根因维持 10.5.4（500 + 不泄露细节）。`OptimisticLockingFailureException` 因更具体仍有专属映射，不受影响。
5. **计数与排序解耦**：`selectCountByQuery` 不剥离 ORDER BY（count 带 ORDER BY 多数数据库非法）；计数一律独立构造无排序 QueryWrapper（主代码计数点仅 `FlexRepositorySupport.existsById/exists` 与各仓储自定义 `existsBy*`），分页计数由 `paginate` 内部处理（排序 Wrapper 直传）。

## 被否的选项

- **自研/引入启动期 schema 校验替代 ddl-auto=validate**：否决——维护面大且与真实执行面脱节；PG 集成测试用真实 DDL 建表跑全字段 CRUD，同一目标覆盖更实（顺带种子幂等 + 租户隔离），成本已存在。
- **STALE_WRITE 复用 WebError.CONCURRENCY_CONFLICT**：否决——仓储层失败需独立可判定错误码（FR-010 异常分支）；409 语义细分随错误码登记（5.2），框架段与 web 段不混用。
- **service 层前置租户校验以避开异常包装**：否决——守卫必须在数据操作面 fail-closed，绕过 Listener 即留旁路（jdbc/mapper 直写不过校验）；包装在 web 层还原成本一次性且对业务零侵入。
- **在 Exception 兜底 `handleUnexpected` 内做解包**：否决——挂 DataAccessException 由 Spring 按最具体处理器路由，语义分层更清晰；兜底路径维持纯 10.5.4 口径。
- **QueryWrapper 计数前自动剥离 ORDER BY**：否决——Wrapper 复用/可变性语义下实现脆弱；独立构造直白且已有测试锚定（`FlexDataAccessTest` 计数注释与 `FlexRepositorySliceTest` 分页用例）。
