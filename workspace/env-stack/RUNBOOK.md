# RUNBOOK —— 云服务器（k3s 单机 · 4C4G）从零到部署

> 对应决策 `.ai-memory/decisions/2026-11-13-06-k8s-k3s-single-node.md`：k3s 单机、TCR 镜像仓库、
> 4G 内存同一时间只常驻一个环境、服务器不做构建。按顺序执行，⭐ 为人工在控制台/服务器操作。

## 0. 服务器侧一次性和安全

- ⭐ 云安全组只放行 `22/80/443`（80 由 k3s Traefik hostPort 使用；443 预留 TLS）
- ⭐（可选，4G 建议）加 2G swap 兜底突发，k3s 默认容忍 swap：
  ```sh
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab && sysctl vm.swappiness=10
  ```

## 1. 安装 k3s（国内镜像源）

```sh
curl -sfL https://rancher-mirror.rancher.cn/k3s/k3s-install.sh | INSTALL_K3S_MIRROR=cn sh -
# 验证
k3s kubectl get node -w   # 就绪后 Ctrl-C
echo "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml" >> ~/.bashrc   # 或 alias kubectl='k3s kubectl'
```

## 2. 安装 kustomize

不需要——`deploy.sh` 用 kubectl 内置 kustomize（临时目录注入镜像地址），零额外二进制。

## 3. 开通腾讯云 TCR 个人版 ⭐

1. TCR 控制台 → 个人版 → 创建**命名空间**（下称 `acme-ns`）
2. 建五个**私有**仓库：`gateway`、`platform-auth`、`platform-infra`、`platform-system`、`web`
3. 访问凭证 → 固定密码（记入 registry.env）
4. REGISTRY 地址形如 `ccr.ccs.tencentyun.com/acme-ns`

## 4. 服务器凭证文件 ⭐

```sh
mkdir -p /opt/acme/dev
# 从仓库 env-stack/config/*.example.env 复制为 app.env / registry.env，填真实值
# 注意权限：chmod 600 /opt/acme/dev/*.env
```

> ⚠️ **既有服务器迁移项（2026-11-14）**：app.example.env 新增 3 个种子密码键
> （`ACME_SEED_SUPERADMIN_PASSWORD` / `ACME_SEED_DEMO_ADMIN_PASSWORD` / `ACME_SEED_DEMO_OPERATOR_PASSWORD`），
> 且服务镜像已无仓库内默认值——**旧 app.env 须补齐这 3 键后才能部署新镜像**，否则 platform-auth 启动即失败。
> 值只影响首次建库；已有库的登录密码不随该值变化（改密走接口）。

## 5. 服务器取代码

```sh
mkdir -p /opt/acme/src && cd /opt/acme/src
git clone <仓库地址> && git checkout acme-foundation   # 受保护主线（main 为历史归档分支，不使用）
```

## 6. 构建并推送镜像（开发机执行，服务器不构建）

```sh
cd workspace/apps/backend
for m in "acme-gateway gateway" \
         "acme-platform/platform-auth/platform-auth-service platform-auth" \
         "acme-platform/platform-infra/platform-infra-service platform-infra" \
         "acme-platform/platform-system/platform-system-service platform-system"; do
  set -- $m
  docker build --build-arg MODULE_PATH=$1 -t $REGISTRY/$2:dev-latest .
done
cd ../frontend
docker build -f Dockerfile.web -t $REGISTRY/web:dev-latest .
docker push $REGISTRY/gateway:dev-latest $REGISTRY/platform-auth:dev-latest \
            $REGISTRY/platform-infra:dev-latest $REGISTRY/platform-system:dev-latest \
            $REGISTRY/web:dev-latest
# docker push 一次只能推一个仓库时逐个执行
```

## 7. 部署与验证（服务器）

```sh
cd /opt/acme/src/workspace/env-stack
sh scripts/deploy.sh dev        # 部署 + 种子 Nacos + 等待 rollout
sh scripts/healthcheck.sh dev   # Pod Ready + web 探活
kubectl -n acme-dev get pods    # 人工复核
```

本机访问（域名分层见决策 `.ai-memory/decisions/2026-11-14-25-domain-routing.md`）：
DNS 控制台给 `dev.acmecloud.site` 加 A 记录指向服务器 IP 后，浏览器直接打开 `http://dev.acmecloud.site`
（子域无 DNS 记录时可临时 hosts 文件兜底：`<服务器IP> dev.acmecloud.site`；
登录走网关 `/admin/v1/auth/tokens`，演示凭证见后端 `V4__seed_demo_credentials.sql`）。
test/prod 同型：`http://test.acmecloud.site` / `http://acmecloud.site`（prod 根域 A 记录已生效，下次 prod 部署后可用）。

## 8. 4G 日常运维要点

- **看状态**：`sh scripts/status.sh dev`（Pod/镜像版本/资源/探活一屏总览；全部环境 `kubectl get pods -A | grep '^acme-'`）
- **换环境**：test 部署前先 `kubectl scale deployment --all --replicas=0 -n acme-dev`
- **看日志**：`k3s kubectl -n acme-dev logs -f deploy/acme-gateway`
- **释放内存**：`kubectl -n acme-dev scale deployment --all --replicas=0`
- **探针 404**：若 actuator health groups 未暴露，把 deployment 探针 path 换成 `/actuator/health`
- **回滚**：改 registry.env 的 `ACME_IMAGE_TAG` 后重跑 deploy.sh，或 `kubectl rollout undo`

## 8.5 本地联调云端环境（dev-tunnel）

`sh scripts/dev-tunnel.sh dev` 一条命令建立本地 ↔ 云端隧道（PG/Redis/Nacos/网关映射到本机
回环，并打印云端凭证的 export 行），Ctrl-C/断连自动拆除（远端 trap 清理）；`down` 子命令按
PID 文件 + 端口监听扫杀服务器侧残留（兜底异常断连）。
适用场景：本地 IDE 断点调试后端、直连云端数据与中间件——避免在 4G 服务器上跑开发进程。
当前运行环境声明见 `.ai/ai-coding-config.yml` 的 `environment.file`（指向 `.ai/env/cloud-dev.yaml`）。

- **前置**⭐：`export ACME_SRV_HOST=<服务器IP>`（必填，脚本无缺省值）；可选 `ACME_SRV_PORT`(22) / `ACME_SRV_USER`(ubuntu) / `ACME_SRV_KEY`(~/.ssh/acme_srv_deploy)
- **后端**：export 凭证后 `PG_HOST=127.0.0.1 PG_PORT=15432 REDIS_HOST=127.0.0.1 REDIS_PORT=16379 NACOS_ADDR=http://127.0.0.1:18848 mvn ... spring-boot:run`（完整示例见脚本输出）
- **前端**：`WEB_API_TARGET=http://127.0.0.1:19888 pnpm dev`（vite 已预留该代理变量；也可直指公网域名）
- 服务器侧监听 27xxx 高位端口（仅绑 127.0.0.1），与 seed-nacos/部署并发互不干扰
- 本地服务会以同名注册进云端 Nacos（dev 允许读写；网关 lb 可能路由到本地实例，属调试期预期行为）
- **prod 禁用**（只读红线，脚本代码级拦截）；**凭证输出由用户终端承接，不得经 AI 工具运行或转录**

## 9. 与 CD 流水线衔接（已接线）

`cd.yml` 已实现完整链路：build 作业在 GitHub 云端 runner 构建五镜像并推 TCR（不占服务器资源），
deploy 作业 scp 同步 env-stack 到服务器后 SSH 执行 `deploy.sh` / `healthcheck.sh`。
CI 部署专用公钥已在服务器 `~/.ssh/authorized_keys`（注释 `acme-ci-deploy`）。

- **平台接入（GitHub 侧一次性人工操作）**：建私有仓 → Gitee 配推送镜像 → 配 7 个 Secrets →
  建 dev/test/prod Environments（prod 加审批人）——详细步骤见 `.ai/ci/CI-GUIDE.md`「二期平台接入 checklist」。
- **触发方式**：dev/test 用 GitHub → Actions → cd → Run workflow（选环境）；prod 打 `v*` 标签自动部署（Environments 审批拦截）。
- 本 runbook 第 6 步（手动构建推送）在流水线可用后仅作应急手段保留。
