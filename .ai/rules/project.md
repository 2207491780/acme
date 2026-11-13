# 项目上下文

## 当前实现

ACME 是全栈应用（非纯本地演示）：前端多端 monorepo + Java 后端 + k8s 部署栈，经 CI/CD 真实部署。各端技术栈/命令/约定以分层 `AGENTS.md`（frontend/backend/ui-prototype）及同目录总览（FRONTEND/BACKEND/UI-PROTOTYPE.md）为准。

- 前端 `workspace/apps/frontend/`：pnpm monorepo（Node≥22.13、pnpm11.8、TS5、React18、Vite5、AntD5、Zustand、Electron37、Expo52/RN0.76；Vitest/node:test）；`packages/core` 框架无关业务+令牌、`web` React UI、`desktop` 套壳 Web 产物、`mobile` RN 原生；已接后端 API（auth/admin/audit）。未接入 TanStack Query/antd-mobile/electron-vite，勿假定存在。
- 后端 `workspace/apps/backend/`：Java25 + Spring Boot4.1 多模块 Maven（`acme-parent` + `acme-cloud`：common→framework→platform→business→gateway，`*-api` 契约 / `*-service` 实现）。
- 部署 `workspace/env-stack/`：docker/k8s(base+overlays) + `scripts/deploy.sh`；CD 构建五镜像推 TCR、SSH 部署、prod 人工审批。
- 视觉事实源 `packages/core/src/theme.ts`；改 UI 先读工程内 `UI-SPEC.md`，校验页 `pnpm ui:guide` 生成，禁手改。

## 关键回归

外部数据加载/导入需验证实体、设置及关联；非法输入不得覆盖有效状态。删除撤销不能因 ID 复用丢记录，切换筛选不能误操作隐藏选项；发布目标变化不能沿用旧审批和产物。多窗口修改需检查存储冲突。

## 命令与门禁

- 前端（`frontend/`，pnpm）：`typecheck`/`test`/`build`；入口 `dev`/`desktop:dev`/`mobile:start`；桌面额外 `--filter @acme/desktop check`。
- 后端（`acme-cloud/`）：`mvn test`；Maven 不在 PATH、PG 集成测试需 Docker（详见 backend 分层规则）。
- AI 配置：`node .ai/sync.mjs` 派生 + `--check` 校验；引擎回归 `.ai/tests/`（CI ai-config 作业）；开关 `.ai/ai-coding-config.yml`；同步通过≠钩子已启用。
- 环境连接 `.ai/env/`、搭建脚本 `workspace/env-stack/`；prod 默认只读，禁明文密钥。本地调试时后端连接环境以 `.ai/ai-coding-config.yml` 的 `environment.file` 所指 `.ai/env/` 定义为准。