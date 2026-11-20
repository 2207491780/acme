# 对话式 CD（会话内部署）手册

> 触发：用户在会话中要求部署/发布/回滚（如「发个版」「部署 prod」「回滚到上个版本」）。
> 链路与决策背景：ADR 2026-11-18-03（CI/CD 本地化，cd.yml 已退役）；部署栈细节见
> `workspace/env-stack/RUNBOOK.md` 第 6/7 步。

## 前置（一次性）

- 本机凭证（用户自备，AI 不可读取/转录）：镜像仓库 `REGISTRY` / `REGISTRY_USERNAME` /
  `REGISTRY_PASSWORD`（环境变量或 `.ai-tmp/registry.env`）；SSH 坐标
  `ACME_DEPLOY_HOST`（数据面）/ `ACME_SRV_HOST`（应用面）+ PORT/USER/KEY（对照仓库根
  `cloud-servers.yml`）
- 数据面机 `/opt/acme/prod/{app.env,registry.env}` 已就位（RUNBOOK 第 4 步）
- 镜像 tag 契约：`prod-<sha7>` **不可变 tag**——可变 tag 会「k8s spec 未变不滚动 + 节点缓存旧镜像」
  （2026-11-13 两次实测的教训）

## 步骤

1. **触发（全部由用户在本机终端执行，AI 不代跑——凭证红线）**：
   ```sh
   sh workspace/env-stack/scripts/local-build.sh     # 本机产物 → .ai-tmp/build/prod-<sha7>/
   sh workspace/env-stack/scripts/local-deploy.sh    # 推 TCR → 数据面部署 → 健康检查 → 隧道面同步
   ```
   AI 的职责边界：准备工作（确认分支/测试/产物齐备、给出命令）可以做；**执行这两条脚本由用户
   完成**（脚本读写仓库凭证与 dev.env 生成，属敏感操作）。

   **增量发布（2026-11-20 实测，ee7a6f3）**：`ACME_BUILD_SERVICES` / `ACME_DEPLOY_SERVICES` /
   服务器侧 `ACME_SERVICES`（local-deploy 自动透传）＝空格分隔服务子集，可指定一个或多个
   （缺省=全量五服务，行为不变）；非法名 fail-fast。口径：**只改了某几个服务的代码时只重建/重发
   这些服务**——deploy.sh 对未选中服务在 kustomize images 段**钉住集群当前 tag**（渲染清单不变 →
   存量 Deployment 不滚动；首次部署无存量 Deployment 时回退全局 TAG）。纯前端迭代示例：
   ```sh
   ACME_BUILD_SERVICES="web"  sh workspace/env-stack/scripts/local-build.sh
   ACME_DEPLOY_SERVICES="web" sh workspace/env-stack/scripts/local-deploy.sh
   ```
   注意：tag 取自 `git HEAD` 的 sha7——纯前端需求合流后若 HEAD 已含 env-stack 脚本 chore 提交，
   产物 tag 会是脚本提交的 sha（web 内容与需求 commit 一致，属预期）。核验口径增量版：选中服务
   Pod 名应变化（镜像 tag=新 sha7），未选中服务 **Pod 名应逐字节不变**（未滚动）。
2. **服务器核验**（AI 可执行只读 SSH，或用户自行）：
   ```sh
   bash /opt/acme/src/workspace/env-stack/scripts/status.sh prod   # 数据面机执行
   ```
   铁证三件套：Pod `creationTimestamp` ≈ 本次部署时刻、镜像 tag = `prod-<本次 sha7>`、
   healthcheck 探活 200（探活在服务器本机 Host 头直探节点 80，不受备案拦截影响）。
3. **汇报**：产物 tag + 服务器证据（tag/时间戳/探活码），红绿如实。

## 边界与失败处理

- **prod 审批语义**：GitHub Environments 门禁已随 cd.yml 退役——审批 = 人工执行
  `local-deploy.sh` 本身；AI 不得代跑，也不得代填凭证。
- **构建/部署红灯**：local-build/local-deploy 为同步命令，失败输出即现场；按报错修复后重跑
  （build 幂等重写产物目录；deploy 幂等可重复执行）。
- **回滚**：`ACME_ART_DIR=.ai-tmp/build/<历史tag目录> sh scripts/local-deploy.sh`（tar 复用，免重
  构建），或数据面机 `kubectl rollout undo`。
- **推送纪律**：本次能力相关的 `.ai`/`.ai-memory` 变更推送必须双分支一条命令
  （`git push origin acme-foundation ai-coding-support`，见 parallel-dev.md 竞态条款）。
- **Gitee/GitHub 双推**：`origin` 双 push URL 保持不变——push 触发 ci.yml 门禁；构建发布不依赖
  GitHub Actions。

## 历史教训索引（2026-11-13 首跑实测，仍有效）

- 可变 tag/短 SHA tag → 部署空跑，必须完整 7 位 SHA 不可变 tag
- 双分支分先后推 → mirror-sync 竞态红灯，必须单命令同推
- `hashFiles` 不可用于 job 级 `if` → 守卫下沉步骤级（ci.yml）
