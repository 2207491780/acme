# .ai/ci/ — CI/CD 工作流源

工作流 YAML 的唯一事实源，由 `.ai/sync.mjs` 派生到 `.github/workflows/`（GitHub 固定路径要求）。**本文件不派生**（目录说明）。

## 工作流一览

| 文件 | 触发 | 职责 |
|---|---|---|
| [ci.yml](ci.yml) | push(main) / PR | 门禁：① AI 配置同步校验（`node .ai/sync.mjs --check`）；② 应用构建与测试（TODO 待 `workspace/apps` 技术栈定型后接线） |
| [cd.yml](cd.yml) | 手动 dispatch（选 dev/test/prod）；`v*` 标签 → prod | 构建 → 调 `workspace/env-stack/scripts/deploy.sh` 部署 → `healthcheck.sh` 验证 |
| [ai-rules-sync.yml](ai-rules-sync.yml) | 触及 AI 配置相关文件 | 联动防线·远端层（见 `.ai/ARCHITECTURE.md`） |

## 环境与发布

- GitHub **Environments** 建议创建 `dev` / `test` / `prod` 三个，与 `.ai/env/*.yaml` 三态对齐；**prod 必须配置 required reviewers**（人工批准后才允许部署，对应规则的「prod 只读/审批」纪律）。
- 发布路径：合并 `main` → 打 `vX.Y.Z` 标签 → 自动走 prod 部署；dev/test 用 Actions → cd → Run workflow 手动触发。

## Secrets 约定

- 命名 `<ENV>_<用途>`（如 `PROD_DEPLOY_TOKEN`、`TEST_MCP_TOKEN`），与 `.ai/env/*.yaml` 中的 `${VAR}` 名保持一致。
- 严禁任何明文凭证进 yaml/脚本/仓库（`.ai/rules` 安全红线同样适用于本目录与派生产物）。

## 接线 TODO（应用定型后）

1. 把 `.ai/rules/project.md`「常用命令」的构建/测试命令填入 `ci.yml` 的两个 TODO 步骤。
2. 实现 `env-stack/scripts/deploy.sh` 与 `healthcheck.sh`（当前为显式失败的占位，保证流水线不会"假绿"）。
3. 在 GitHub 仓库 Settings → Environments 创建三环境并为 prod 配审批人。

## 修改纪律

- 改本目录文件 = 改 AI-Coding 配置：改完必须 `node .ai/sync.mjs` 并通过 `--check`（提交钩子与 CI 双重拦截）。
