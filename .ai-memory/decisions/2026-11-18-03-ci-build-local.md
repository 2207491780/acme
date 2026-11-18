# CI/CD 执行本地化：本机构建推 TCR + SSH 触发部署，GitHub Actions cd.yml 退役

> 取代 `2026-11-13-06-k8s-k3s-single-node.md` 的「镜像在开发机或后续 CI 构建」中「后续 CI（GitHub
> Actions）」走向，及 `.ai/ci/cd.yml` 的 GitHub 云端编排接线结论；其「服务器不构建」「脚本契约」
> 结论继续有效。部署拓扑见 `2026-11-18-02-two-tier-single-middleware.md`。

## 背景

用户反馈 GitHub Actions 链路太慢（runner 排队/构建时长），定向改为本地执行构建后推送。此前 cd.yml
已实现 GitHub 云端 runner 构建 + SSH 部署全链路；dev/test 云端部署已随两台云机分层形态退役，cd.yml
实际仅剩 prod 构建 + 部署职责，该职责可由本地脚本完整承接（构建本就在开发机验证过——RUNBOOK 第 6 步
曾为既定路径）。

## 决定

- **构建/打包分离，产物目录为接口**：`env-stack/scripts/local-build.sh` 本机产出构建产物到
  `.ai-tmp/build/<env>-<sha7>/`（ENV-STACK 约定的构建产物位）——backend 四可执行 jar（本机 Maven
  package `-DskipTests`，工具链本机化；测试门禁由 ci.yml 补位）+ frontend web dist（本机 pnpm，口径
  同自包含 Dockerfile.web）+ 五镜像 tar（docker build 消费产物：新增轻量 `Dockerfile(.web).prebuilt`
  仅做 COPY 打包，产物 staging 为独立上下文）+ `meta.env`；**build 阶段零凭证**（镜像名用仓库内
  `acme/*` 命名，registry 前缀留待 deploy 侧 `docker tag`）。tag 沿用不可变 `prod-<sha7>`
  （`2026-11-13-16`）。自包含多阶段 `Dockerfile` / `Dockerfile.web` 保留为独立/应急构建路径。
- **部署消费产物**：`env-stack/scripts/local-deploy.sh` 读 `meta.env` → 镜像缺失先 `docker load`
  （tar，支持跨目录/跨机复用同一份产物，`ACME_ART_DIR` 指定）→ `docker tag` 加 registry 前缀 → 推
  TCR → scp env-stack → SSH 数据面机执行 `deploy.sh prod` + `healthcheck.sh prod`；镜像仓库凭证键名
  与服务器 registry.env 同源（`REGISTRY` / `REGISTRY_USERNAME` / `REGISTRY_PASSWORD`），经本机环境
  变量或 `.ai-tmp/registry.env`（私有文件，不入库，敏感值不经 AI 工具转录）；SSH 坐标经
  `ACME_SRV_*` 环境变量（对照仓库根 `cloud-servers.yml`，与 dev-tunnel 同约定）。
- **cd.yml 退役**：删除 `.ai/ci/cd.yml` 与派生 `.github/workflows/cd.yml`；`ci.yml`（门禁：测试/
  ai-config 校验）保留；代码双推 Gitee/GitHub 不变——GitHub 仅作代码托管与 CI 门禁，不再承担构建
  发布。
- **接受的代价（记录在案）**：失去 prod GitHub Environments 人工审批门禁（审批语义 = 人工执行
  deploy 步骤本身）与云端流水线可观测性（本地脚本输出承接）；prod 部署改用人工保管 SSH 密钥（原
  CI 专用部署密钥职责回归人）。

## 被否的选项

- **服务器 A/B 装 self-hosted runner 承接构建**：4G 常驻机余量 ≈1.5Gi，构建峰值 1–2Gi/模块必挤压
  prod 中间件/服务（与 `2026-11-13-06` 否决服务器构建同口径）；且 runner 在 prod 数据面机执行仓库
  触发代码属安全面扩大。
- **Gitee Go 构建党制**：双 CI 维护成本，且「GitHub 太慢」的链路感受（网络排障）同样适用于 Gitee。
- **服务器 A 轮询 tag 自触发部署**：失去审批门禁，且需自实现轮询/去重/重试；GitHub Actions 仍承担
  ci.yml 门禁，无需走到这一步。
- **保留 cd.yml 作为应急通道**：双链路并存需双份 secrets 与文档维护，与「旧方案直接删除」定向冲突。
