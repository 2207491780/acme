# ACME 后端

Java 25 + Spring Boot 4.1 多模块 Maven 工程：`acme-parent`（依赖/插件父）、`acme-cloud`（聚合：
acme-common 公共池、acme-framework 框架层、acme-platform 平台服务、acme-business 业务域、acme-gateway 网关）。
根 `AGENTS.md`（由 `.ai/RULES.md` 派生）始终生效，本文件只做后端补充。

## 约定（详细口径见手册）

- **新增服务或编写仓储/集成测试前，先读 `.ai-memory/playbooks/flex-test-assembly.md`**（三层测试环境
  与装配踩坑口径）；数据访问取舍背景见 `.ai-memory/decisions/2026-11-12-02-flex-data-access-residuals.md`。
- 仓储实现继承 `FlexRepositorySupport`；删除统一经 `FlexDeletes`（绑定表物理删除走旁路）。
- 计数用独立无排序 QueryWrapper（`selectCountByQuery` 不剥离 ORDER BY；分页用 `paginate`）。
- PG 集成测试无 Docker 自动跳过——跳过不等于验证，合并前必须在有 Docker 环境跑过。
- 构建与全量测试：`acme-cloud/` 下 `mvn test`；Maven 不在 PATH
  （`"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd"`）。
- 模块结构：`*-api` 对外契约、`*-service` 实现与部署单元；分层 common→framework→platform→business→gateway。
  总览见同目录 `BACKEND.md`，命名见 `.ai-memory/playbooks/naming.md`。

<!-- 派生自 .ai/scopes/workspace/apps/backend/AGENTS.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
