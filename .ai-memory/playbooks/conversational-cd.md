# 对话式 CD（会话内部署）手册

> 触发：用户在会话中要求部署/CD（如「部署 dev」「发测试环境」「回滚到上个版本」）。
> 链路与决策背景：ADR 2026-11-13-07/08；部署栈细节见 `workspace/env-stack/RUNBOOK.md`。

## 前置（一次性，均已就位）

- `ACME_GH_TOKEN`：Windows 用户级环境变量，fine-grained PAT（仅 acme 仓 Actions: rw）——触发与轮询用
- 服务器 SSH 密钥 `~/.ssh/acme_srv_deploy`（公钥已在服务器 `authorized_keys`）——核验用
- 凭证经 `.ai-tmp/load-acme-env.sh` 从注册表加载到会话内存（不回显、不入库、不入对话）
- 镜像 tag 契约：`<env>-<sha7>` **不可变 tag**——可变 tag 会"k8s spec 未变不滚动 + 节点缓存旧镜像"，
  CI 绿而代码不达（2026-11-13 两次实测的教训）

## 步骤

1. **触发**（云端构建，本地零负载）：
   ```sh
   curl -s -o /dev/null -w "%{http_code}" -X POST \
     -H "Authorization: Bearer $ACME_GH_TOKEN" -H "Accept: application/vnd.github+json" \
     https://api.github.com/repos/2207491780/acme/actions/workflows/cd.yml/dispatches \
     -d '{"ref":"acme-foundation","inputs":{"env":"dev"}}'
   # 204 = 已接受；ref 取 acme-foundation（默认分支）
   ```
2. **轮询**：`GET /repos/2207491780/acme/actions/runs?per_page=1` 取最新 run（记 id 与
   head_sha），再轮询 `GET .../runs/{id}` 至 `status=completed && conclusion=success`；
   全程约 6–8 分钟，每 30s 一查。run 页面 URL 形如 `.../actions/runs/{id}`。
3. **服务器核验**（SSH 后）：
   ```sh
   bash /opt/acme/src/workspace/env-stack/scripts/status.sh dev
   ```
   铁证三件套：Pod `creationTimestamp` ≈ 本次运行时刻、镜像 tag = `dev-<本次 sha7>`、探活 200。
4. **汇报**：run 链接 + 服务器证据（tag/时间戳/探活码），红绿如实。

## 边界与失败处理

- **prod 强制审批**：GitHub Environments 服务端拦截，API 触发同样卡在「等待批准」——红线不可绕，
  也不要试图绕（prod 环境尚未创建，打 `v*` 标签前必须先建环境并配 Required reviewers）。
- **运行红灯**：`GET .../runs/{id}/logs`（zip）或请用户贴失败步骤日志 → 修复 → 重新 dispatch。
- **回滚**：以旧 commit 触发 dispatch（tag 指向旧镜像即回滚），或服务器 `kubectl rollout undo`。
- **token 卫生**：只从环境变量读；过期/泄露在 GitHub → Settings → Developer settings 吊销重建。
- **推送纪律**：本次能力相关的 `.ai`/`.ai-memory` 变更推送必须双分支一条命令
  （`git push origin acme-foundation ai-coding-support`，见 parallel-dev.md 竞态条款）。

## 历史教训索引（均为 2026-11-13 首跑实测）

- `hashFiles` 不可用于 job 级 `if` → 守卫下沉步骤级（ci.yml）
- 新仓首推 workflow 注册可能异常 → 空提交重推触发重索引
- 可变 tag/短 SHA tag → 部署空跑，必须完整 7 位 SHA 不可变 tag
- 双分支分先后推 → mirror-sync 竞态红灯，必须单命令同推
