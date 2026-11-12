# 云上 CI/CD 分两期：一期只做 Docker 运行环境，CI 平台延后决策

## 背景

仓库托管在 Gitee，而 `.ai/ci/` 派生的 `.github/workflows/` 是 GitHub Actions 格式——Gitee 不执行，
CI 门禁实际悬空。`cd.yml` 的 build 作业为 TODO 占位，`env-stack/scripts/deploy.sh`/`healthcheck.sh`
为显式 `exit 1` 的防假绿占位，`env-stack/docker/`、`config/` 为空目录。团队已有国内云服务器
（阿里云/腾讯云类），诉求是搭建云服务器 CI/CD 与 Docker 运行环境。目标运行栈：Java 25 多模块
后端（PostgreSQL + Redis + Nacos）+ Vite 静态 web（nginx 托管）；Electron/RN 不上服务器。

## 决定

分两期，公共前置先行：

- **一期（与 CI 平台解耦）**：云服务器 Docker 运行环境（Docker Engine + Compose 插件、registry
  mirror、`/opt/acme/{dev,test,prod}/` 单机多环境）＋ 容器化产物（后端多阶段 Dockerfile、web
  Dockerfile、`env-stack/docker/compose.{dev,test,prod}.yml`、`config/` 占位模板）＋ 实现
  `deploy.sh`/`healthcheck.sh`，手动触发跑通。镜像仓库用**阿里云 ACR**（国内服务器拉取快且稳）。
- **二期**：CI 执行平台接线，届时在「双托管 GitHub Actions / 服务器自建 Gitea+act_runner /
  Gitee Go」中按一期后的实际情况选择；现有 `.github/workflows` 三条 yml 届时再定去留。

## 被否的选项

- **一期同时定死 CI 平台**：容器化与镜像化是三个 CI 方案的公共前置，先做不浪费；反之先建平台
  会在占位脚本上空转。平台选择涉及 `.ai/ci/` 派生体系扩展（ai-config 联动流程），成本应后置。
- **双托管 GitHub Actions 作为一期目标**：现有流水线零改造是优势，二期仍是首选候选；但当前
  服务器在国内且诉求先落运行环境，双仓同步与门禁验证对一期无增益。
- **服务器自建 Gitea/act_runner**：构建负载压业务机（Java 25 + pnpm + testcontainers 建议
  4C8G 起），且 `.ai/ci/` 需新增派生目标，改造成本前置，二期再议。
- **Gitee Go**：三条 workflow 语法独立需全部重写，免费额度有限，二期再议。
- **GHCR 作镜像仓库**：国内服务器拉取慢且不稳，与 ACR 相比无优势（Actions runner 推 ACR 不受影响）。
