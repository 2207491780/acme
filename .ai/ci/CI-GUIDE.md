# .ai/ci/ — CI/CD 工作流源

工作流 YAML 的唯一事实源，由 `.ai/sync.mjs` 派生到 `.github/workflows/`（GitHub 固定路径要求）。**本文件不派生**（目录说明）。

## 工作流一览

| 文件 | 触发 | 职责 |
|---|---|---|
| [ci.yml](ci.yml) | push(全分支) / PR | 门禁：① AI 配置同步校验（`node .ai/sync.mjs --check`）+ 派生引擎回归测试（`.ai/tests/`），**全分支触发**；② **镜像一致性**（仅两条策略分支）：AI 路径在 `acme-foundation` ↔ `ai-coding-support` 必须逐字节一致；③ `workspace/apps/frontend` 的类型检查 / 测试 / 桌面壳层语法检查 / 构建，**限 main 直推与 PR**，且分支无前端工程时自动跳过（`hashFiles` 守卫）；④ `workspace/apps/backend` 的 `mvn test`（Java 25，PG 集成测试走 testcontainers），同口径门控 |
| [cd.yml](cd.yml) | 手动 dispatch（选 dev/test/prod）；`v*` 标签 → prod | 构建 → 调 `workspace/env-stack/scripts/deploy.sh` 部署 → `healthcheck.sh` 验证 |
| [ai-rules-sync.yml](ai-rules-sync.yml) | 触及 AI 配置相关文件 | 联动防线·远端层（见 `.ai/ARCHITECTURE.md`） |

## 环境与发布

- GitHub **Environments** 建议创建 `dev` / `test` / `prod` 三个，与 `.ai/env/*.yaml` 三态对齐；**prod 必须配置 required reviewers**（人工批准后才允许部署，对应规则的「prod 只读/审批」纪律）。
- 发布路径：合并 `main` → 打 `vX.Y.Z` 标签 → 自动走 prod 部署；dev/test 用 Actions → cd → Run workflow 手动触发。

## Secrets 约定

- 命名 `<ENV>_<用途>`（如 `PROD_DEPLOY_TOKEN`、`TEST_MCP_TOKEN`），与 `.ai/env/*.yaml` 中的 `${VAR}` 名保持一致。
- 严禁任何明文凭证进 yaml/脚本/仓库（`.ai/rules` 安全红线同样适用于本目录与派生产物）。

## 接线 TODO（部署链路）

1. ~~把构建/测试命令填入 `ci.yml`~~（已完成：`app-quality` 作业接线 `workspace/apps/frontend` 的 pnpm 命令）。
2. 实现 `env-stack/scripts/deploy.sh` 与 `healthcheck.sh`（当前为显式失败的占位，保证流水线不会"假绿"）。
3. 在 GitHub 仓库 Settings → Environments 创建三环境并为 prod 配审批人。

## 修改纪律

- 改本目录文件 = 改 AI-Coding 配置：改完必须 `node .ai/sync.mjs` 并通过 `--check`（提交钩子与 CI 双重拦截）。

## 团队接入 checklist（平台侧人工操作）

仓库内的规范与钩子只覆盖本机与 CI；以下在托管平台（Gitee/GitHub）后台完成，形成最后一道兜底：

1. **`main` 分支保护**：禁直推（仅允许 PR 合并）、开启合并前 CI 检查（对应本文件 ci.yml 两个作业）、禁强推与删分支。
2. **PR 审核**：至少 1 人批准方可合并（对应 `.ai/rules/git-workflow.md`「CR 未通过前不得合并」）。
3. **成员本机初始化**：克隆后执行 `node .ai/hooks/enable.mjs` 启用本地钩子（pre-commit / commit-msg / pre-push）。
4. **AI 工具协作**：AI 遵守 `.ai/rules/git-workflow.md`「AI 操作授权分级」；AI 参与的提交带 `Assisted-by: <工具名>` trailer，PR 描述注明 AI 改动。
