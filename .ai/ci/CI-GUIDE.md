# .ai/ci/ — CI/CD 工作流源

工作流 YAML 的唯一事实源，由 `.ai/sync.mjs` 派生到 `.github/workflows/`（GitHub 固定路径要求）。**本文件不派生**（目录说明）。

## 工作流一览

| 文件 | 触发 | 职责 |
|---|---|---|
| [ci.yml](ci.yml) | push(全分支) / PR | 门禁：① AI 配置同步校验（`node .ai/sync.mjs --check`）+ 派生引擎回归测试（`.ai/tests/`），**全分支触发**；② **镜像一致性**（仅两条策略分支）：AI 路径在 `acme-foundation` ↔ `ai-coding-support` 必须逐字节一致（pre-push 已做本地预检，CI 兜底）；③ `workspace/apps/frontend` 的类型检查 / 测试 / 桌面壳层语法检查 / 构建，**限主线 `acme-foundation` 直推与 PR**，且分支无前端工程时自动跳过（`hashFiles` 守卫）；④ `workspace/apps/backend` 的 `mvn test`（Java 25，PG 集成测试走 testcontainers），同口径门控 |

> 原 `cd.yml` 已退役（ADR 2026-11-18-03 CI/CD 本地化）：构建发布改由本机 `local-build.sh` /
> `local-deploy.sh` 承接（见 `workspace/env-stack/RUNBOOK.md` 第 6/7 步）；GitHub 仅作代码托管与
> ci.yml 门禁。原 `ai-rules-sync.yml` 亦已裁撤（ADR 2026-11-14-23）。

> 原独立的 `ai-rules-sync.yml` 已裁撤（ADR 2026-11-14-23）：其校验职责被 ci.yml 的 ai-config 作业完全覆盖（同校验 + 引擎回归测试，且全分支触发）。

## 环境与发布（CI/CD 本地化后）

- 构建发布已本地化（ADR 2026-11-18-03）：本机 `local-build.sh` 产物 → `local-deploy.sh` 推 TCR +
  SSH 数据面部署；prod 审批语义 = **人工执行 local-deploy.sh**（AI 不代跑——凭证红线）。
  GitHub Environments（dev/test/prod）与部署类 Secrets 均已不需要，可删除。
- ci.yml 仅承担**门禁**（测试 / ai-config 校验 / 双分支镜像一致性）；发布路径：合并
  `acme-foundation` → 本机构建部署（RUNBOOK 第 6/7 步）。

## 凭证约定（本地化后）

- 不再使用 GitHub 仓库 Secrets 承载部署凭证；本机凭证不入库：
  - 镜像仓库：本机环境变量 `REGISTRY` / `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`（与服务器
    `registry.env` 同键名），或 `.ai-tmp/registry.env` 私有文件；
  - SSH 坐标：`ACME_DEPLOY_HOST`（数据面）/ `ACME_SRV_HOST`（应用面，dev-tunnel 对端）+ 各自
    PORT/USER/KEY，对照仓库根 `cloud-servers.yml`；
  - dev 凭证由 `local-deploy.sh` 从集群 Secret 提取生成 `dev.env` 同步应用面机（用户执行，敏感
    输出不经 AI 工具转录）。
- 严禁任何明文凭证进 yaml/脚本/仓库（`.ai/rules` 安全红线同样适用于本目录与派生产物）。

## 接线 TODO（部署链路）

1. ~~把构建/测试命令填入 `ci.yml`~~（已完成：`app-quality` 作业接线 `workspace/apps/frontend` 的 pnpm 命令）。
2. ~~实现 `env-stack/scripts/deploy.sh` 与 `healthcheck.sh`~~（已完成：k3s 口径真实现，`status.sh` 一并就位）。
3. ~~`cd.yml` build/deploy 作业接线~~（已完成并**已退役**：CI/CD 本地化，见 `env-stack/scripts/local-build.sh` / `local-deploy.sh`）。
4. 平台侧人工接入（GitHub 侧，见下方二期 checklist）。

## 二期平台接入 checklist（GitHub 侧人工操作，执行一次）

1. **GitHub 建私有仓库**（空仓即可，如 `acme`；默认分支建议设为 `acme-foundation`）。
2. **本地配双远端**（取代平台侧镜像同步，见 ADR 2026-11-13-08）：
   ```sh
   git remote set-url --add --push origin <Gitee仓库URL>    # 先显式钉住 Gitee
   git remote set-url --add --push origin <GitHub仓库URL>   # 追加 GitHub → 一条 push 双推
   ```
   此后 `git push origin <分支>` 同时命中两仓：Gitee 供国内拉取，GitHub 触发 ci.yml 门禁。
   需推送 `acme-foundation` 与 `ai-coding-support` 两分支（mirror-sync 依赖同在）。
3. ~~GitHub 仓库 Secrets（部署类）~~：CI/CD 本地化后不再需要（凭证走本机环境变量 / `.ai-tmp/`）。
4. ~~GitHub Environments~~：cd.yml 退役后已不需要，可删除。
5. **验证**：GitHub → Actions → ci → 门禁全绿；发布验证按 RUNBOOK 第 6/7 步本机执行。

## 修改纪律

- 改本目录文件 = 改 AI-Coding 配置：改完必须 `node .ai/sync.mjs` 并通过 `--check`（提交钩子与 CI 双重拦截）。

## 团队接入 checklist（平台侧人工操作）

仓库内的规范与钩子只覆盖本机与 CI；以下在托管平台（Gitee/GitHub）后台完成，形成最后一道兜底：

1. **`acme-foundation` 分支保护**（受保护主线，`main` 为历史归档分支）：禁直推（仅允许 PR / 快进合流）、开启合并前 CI 检查（对应本文件 ci.yml 两个作业）、禁强推与删分支。
2. **PR 审核**：至少 1 人批准方可合并（对应 `.ai/rules/git-workflow.md`「CR 未通过前不得合并」）。
3. **成员本机初始化**：克隆后执行 `node .ai/hooks/enable.mjs` 启用本地钩子（pre-commit / commit-msg / pre-push）。
4. **AI 工具协作**：AI 遵守 `.ai/rules/git-workflow.md`「AI 操作授权分级」；AI 参与的提交带 `Assisted-by: <工具名>` trailer，PR 描述注明 AI 改动。
