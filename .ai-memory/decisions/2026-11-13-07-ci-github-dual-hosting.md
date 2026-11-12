# 二期 CI 平台定为 GitHub 双托管，CD 经 SSH 触发服务器部署

> 收敛 `2026-11-13-06-k8s-k3s-single-node.md` 中「二期 CI 平台接线（届时在 A/B/C 中选择）」的悬置项。

## 背景

一期 Docker/k3s 运行环境已交付且手动链路（构建→推 TCR→deploy.sh→healthcheck）全部跑通。
CI 平台三选一悬而未决：A GitHub 双托管 / B 服务器自建 Gitea+act_runner / C Gitee Go。
用户选定 A。约束：仓库托管在 Gitee（不执行 GitHub Actions）；4G 单机不可承担构建
（06 号决策「服务器不构建」纪律）。

## 决定

- **执行平台**：GitHub Actions（双托管）——Gitee 保持源仓并配置「仓库镜像管理」推送镜像到
  GitHub 私有仓（源仓、规范、钩子、双分支镜像纪律均在 Gitee 不变）；构建在 GitHub 云端
  runner 执行，满足「服务器不构建」。
- **CD 链路**：`cd.yml` build 作业构建五镜像推 TCR（tag `<env>-latest`）；deploy 作业
  scp 同步 `workspace/env-stack` 到服务器后 SSH 执行 `deploy.sh`/`healthcheck.sh`
  （服务器清单以流水线同步为准）。dev/test 手动 dispatch，prod 走 `v*` 标签 + GitHub
  Environments required reviewers 审批。
- **凭证**：CI 部署专用 SSH 密钥对（`acme-ci-deploy`，公钥已装服务器）+ TCR 访问凭证，
  全部走 GitHub Secrets（7 个，清单见 `.ai/ci/CI-GUIDE.md`）；服务器侧 `/opt/acme/<env>/`
  凭证文件与 TCR 拉取凭证不变。

## 被否的选项

- **B 服务器自建 Gitea+act_runner**：构建负载压垮 4G 单机，与「服务器不构建」纪律冲突；
  除非未来加独立构建机。
- **C Gitee Go 重写**：三条 workflow 语法独立全部重写 + `.ai/ci/` 新增派生目标 + 无
  Environments 等价审批 + 免费额度有限，性价比最低。
- **deploy 用 rsync 增量**：scp -r 全量同步 env-stack（纯文本清单，体积小），少一个
  服务器侧依赖，幂等简单。
