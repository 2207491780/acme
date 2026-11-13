# .ai/ci/ — CI/CD 工作流源

工作流 YAML 的唯一事实源，由 `.ai/sync.mjs` 派生到 `.github/workflows/`（GitHub 固定路径要求）。**本文件不派生**（目录说明）。

## 工作流一览

| 文件 | 触发 | 职责 |
|---|---|---|
| [ci.yml](ci.yml) | push(全分支) / PR | 门禁：① AI 配置同步校验（`node .ai/sync.mjs --check`）+ 派生引擎回归测试（`.ai/tests/`），**全分支触发**；② **镜像一致性**（仅两条策略分支）：AI 路径在 `acme-foundation` ↔ `ai-coding-support` 必须逐字节一致（pre-push 已做本地预检，CI 兜底）；③ `workspace/apps/frontend` 的类型检查 / 测试 / 桌面壳层语法检查 / 构建，**限主线 `acme-foundation` 直推与 PR**，且分支无前端工程时自动跳过（`hashFiles` 守卫）；④ `workspace/apps/backend` 的 `mvn test`（Java 25，PG 集成测试走 testcontainers），同口径门控 |
| [cd.yml](cd.yml) | 手动 dispatch（选 dev/test/prod）；`v*` 标签 → prod | ① build：云端 runner 构建五镜像（后端×4 + web）并推 TCR（不占 4G 服务器构建资源）；② deploy：scp 同步 `workspace/env-stack` 到服务器 → SSH 执行 `deploy.sh` → `healthcheck.sh` 验证（非零退出阻断） |
| [ai-rules-sync.yml](ai-rules-sync.yml) | 触及 AI 配置相关文件 | 联动防线·远端层（见 `.ai/ARCHITECTURE.md`） |

## 环境与发布

- GitHub **Environments** 建议创建 `dev` / `test` / `prod` 三个，与 `.ai/env/*.yaml` 三态对齐；**prod 必须配置 required reviewers**（人工批准后才允许部署，对应规则的「prod 只读/审批」纪律）。
- 发布路径：合并 `acme-foundation` → 打 `vX.Y.Z` 标签 → 自动走 prod 部署；dev/test 用 Actions → cd → Run workflow 手动触发。

## Secrets 约定

- 跨环境凭证命名 `<系统>_<用途>`（部署链路实际使用，在 GitHub 仓库 Secrets 配置）：

  | Secret | 值 |
  |---|---|
  | `TCR_REGISTRY` | `ccr.ccs.tencentyun.com/<命名空间>`（含命名空间，不含镜像名） |
  | `TCR_REGISTRY_HOST` | `ccr.ccs.tencentyun.com`（仅域名，docker login 用） |
  | `TCR_USERNAME` / `TCR_PASSWORD` | TCR 访问凭证 |
  | `DEPLOY_SSH_HOST` / `DEPLOY_SSH_PORT` / `DEPLOY_SSH_USER` | 服务器公网 IP / SSH 端口 / 用户名 |
  | `DEPLOY_SSH_KEY` | CI 部署专用私钥（公钥已装服务器 `authorized_keys`，注释 `acme-ci-deploy`） |

  环境相关凭证（若后续需要）仍按 `<ENV>_<用途>`，与 `.ai/env/*.yaml` 的 `${VAR}` 名保持一致。
- 严禁任何明文凭证进 yaml/脚本/仓库（`.ai/rules` 安全红线同样适用于本目录与派生产物）。

## 接线 TODO（部署链路）

1. ~~把构建/测试命令填入 `ci.yml`~~（已完成：`app-quality` 作业接线 `workspace/apps/frontend` 的 pnpm 命令）。
2. ~~实现 `env-stack/scripts/deploy.sh` 与 `healthcheck.sh`~~（已完成：k3s 口径真实现，`status.sh` 一并就位）。
3. ~~`cd.yml` build/deploy 作业接线~~（已完成：build 推 TCR，deploy 经 SSH 触发服务器脚本）。
4. 平台侧人工接入（GitHub 侧，见下方二期 checklist）。

## 二期平台接入 checklist（GitHub 侧人工操作，执行一次）

1. **GitHub 建私有仓库**（空仓即可，如 `acme`；默认分支建议设为 `acme-foundation`）。
2. **本地配双远端**（取代平台侧镜像同步，见 ADR 2026-11-13-08）：
   ```sh
   git remote set-url --add --push origin <Gitee仓库URL>    # 先显式钉住 Gitee
   git remote set-url --add --push origin <GitHub仓库URL>   # 追加 GitHub → 一条 push 双推
   ```
   此后 `git push origin <分支>` 同时命中两仓：Gitee 供国内拉取，GitHub 触发 Actions。
   需推送 `acme-foundation` 与 `ai-coding-support` 两分支（mirror-sync 依赖同在）。
3. **GitHub 仓库 Secrets**：按上表配 7 个（`DEPLOY_SSH_KEY` 内容为本机 `~/.ssh/acme_ci_deploy` 私钥全文）。
4. **GitHub Environments**：建 `dev` / `test` / `prod`；`prod` 加 Required reviewers（对应「prod 只读/审批」纪律）。
5. **验证**：GitHub → Actions → cd → Run workflow（选 `acme-foundation` 分支、env=dev）→ 观察构建与部署全绿。

## 修改纪律

- 改本目录文件 = 改 AI-Coding 配置：改完必须 `node .ai/sync.mjs` 并通过 `--check`（提交钩子与 CI 双重拦截）。

## 团队接入 checklist（平台侧人工操作）

仓库内的规范与钩子只覆盖本机与 CI；以下在托管平台（Gitee/GitHub）后台完成，形成最后一道兜底：

1. **`main` 分支保护**：禁直推（仅允许 PR 合并）、开启合并前 CI 检查（对应本文件 ci.yml 两个作业）、禁强推与删分支。
2. **PR 审核**：至少 1 人批准方可合并（对应 `.ai/rules/git-workflow.md`「CR 未通过前不得合并」）。
3. **成员本机初始化**：克隆后执行 `node .ai/hooks/enable.mjs` 启用本地钩子（pre-commit / commit-msg / pre-push）。
4. **AI 工具协作**：AI 遵守 `.ai/rules/git-workflow.md`「AI 操作授权分级」；AI 参与的提交带 `Assisted-by: <工具名>` trailer，PR 描述注明 AI 改动。
