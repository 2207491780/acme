# RUNBOOK —— 两台 4C4G 云机（两层机型 · k3s 两节点）从零到部署

> 对应决策 `.ai-memory/decisions/2026-11-18-02-two-tier-single-middleware.md`（两层机型 + 单套中间件
> 两环境逻辑隔离：prod / dev，test 并入 dev）与 `2026-11-18-03-ci-build-local.md`（CI/CD 本地化：
> 本机构建推 TCR + SSH 部署，GitHub Actions cd.yml 已退役）。**服务器不做构建**。
> 安全组细则与实施步骤见 `.ai-memory/playbooks/two-tier-deployment.md`。按顺序执行，⭐ 为人工操作。
> 两台机器地址/账号对照仓库根 `cloud-servers.yml`（gitignore 不入库）。

## 0. 两台服务器一次性与安全 ⭐

- ⭐ 两台为**轻量应用服务器（Lighthouse）**、可分属不同账号——无内网互通；组网走**公网 IP +
  k3s wireguard-native 加密后端**（6443/10250 本身 TLS，跨机封装流量走 wireguard UDP 51820）。
  **禁止 VXLAN 等明文协议跨公网**。
- ⭐ 轻量防火墙规则（源 = 对方公网 IP）：数据面入站 `TCP 6443`、`UDP 51820`、`TCP 10250` ← 应用面；
  应用面入站 `UDP 51820`、`TCP 10250` ← 数据面。公网面既有：数据面 `22/80/443`、应用面 `22`。
- ⭐ 两台各加 2G swap 兜底突发（4G 必配），k3s 默认容忍 swap：
  ```sh
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab && sysctl vm.swappiness=10
  ```

## 1. 安装 k3s（国内镜像源）⭐

```sh
# 数据面（k3s server；公网 node IP + wireguard 后端）
PUB=$(curl -s http://metadata.tencentyun.com/latest/meta-data/public-ipv4)
curl -sfL https://rancher-mirror.rancher.cn/k3s/k3s-install.sh | \
  INSTALL_K3S_MIRROR=cn sh -s - server --flannel-backend=wireguard-native \
  --node-ip=$PUB --node-external-ip=$PUB --node-label acme/role=env
# 应用面（k3s agent join，指向数据面公网 6443；后端配置由 server 下发，agent 不加 --flannel-backend）
IN=$(curl -s http://metadata.tencentyun.com/latest/meta-data/local-ipv4)
PUB=$(curl -s http://metadata.tencentyun.com/latest/meta-data/public-ipv4)
curl -sfL https://rancher-mirror.rancher.cn/k3s/k3s-install.sh | \
  INSTALL_K3S_MIRROR=cn sh -s - agent --server https://<数据面公网IP>:6443 --token <token> \
  --node-ip=$IN --node-external-ip=$PUB --node-label acme/role=app
# ⚠️ 轻量机公网 IP 为 NAT 绑定：node-ip 用内网、node-external-ip 用公网；--flannel-external-ip 是
# server 专属旗标（agent 会 fatal），agent 的 flannel 公网端点随 --node-external-ip 自动发布。
# 两台均需 /etc/rancher/k3s/registries.yaml（docker.io 走 mirror.ccs.tencentyun.com），否则 pause
# 等沙箱镜像拉取失败（国内无法直连 docker.io，实测教训）。
# 验证（数据面执行）
k3s kubectl get nodes        # 两节点 Ready
echo "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml" >> ~/.bashrc
# 跨节点互通冒烟：两节点各起一个 pod 互 ping（流量经 wireguard 隧道，同地域实测 RTT ≈2–3ms）
```

## 2. 安装 kustomize

不需要——`deploy.sh` 用 kubectl 内置 kustomize（临时目录注入镜像地址），零额外二进制。

## 3. 腾讯云 TCR 个人版（已就绪，复用老环境仓库）

- TCR 归属**应用面所在账号**（账号 ID `100038448417`）；命名空间 **`acme`**；五个**私有**仓库
  `gateway / platform-auth / platform-infra / platform-system / web` 已于 2026-11-13 创建，直接复用。
- 凭证方式：访问凭证页的**长期密码** + 用户名（账号 ID 形式），不使用临时令牌——记入数据面机
  `/opt/acme/prod/registry.env` 与本机 `.ai-tmp/registry.env`（真实值不入库、不经 AI 工具转录）。
- REGISTRY 地址：`ccr.ccs.tencentyun.com/acme`（两台轻量机访问走腾讯内网路径，快且不占公网流量包）。
- ⭐ 首次：确认五仓库「访问类型 = 私有」；长期密码遗失在访问凭证页重置（重置后同步两处 registry.env）。
- 数据面 `/opt/acme/prod/app.env` 由部署侧随机生成（openssl，值只存数据面机）；三 `ACME_SEED_*` 即
  部署后演示账号的登录密码，需要时在数据面机查看。

## 4. 数据面机凭证文件 ⭐

```sh
mkdir -p /opt/acme/prod
# 从仓库 env-stack/config/*.example.env 复制为 app.env / registry.env，填真实值
# 注意权限：chmod 600 /opt/acme/prod/*.env
```

app.env 必含键（缺任一即启动失败，fail-fast 属预期）：
`PG_PASSWORD` / `PG_DEV_PASSWORD` / `REDIS_PASSWORD` / `REDIS_DEV_PASSWORD` / `NACOS_PASSWORD` /
双 HMAC（`ACME_USER_TOKEN_HMAC_SECRET` + `ACME_SECURITY_TOKEN_HMAC_SECRET`，两域必须不同值）/
3 个种子密码键（部署后即演示账号登录密码）/ `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` /
`ACME_STORAGE_ACCESS_KEY` / `ACME_STORAGE_SECRET_KEY`（同 MinIO root 值，服务侧 S3 凭证）/
`KAFKA_ADVERTISED_LISTENERS`（`INTERNAL://kafka:29092,EXTERNAL://<数据面公网IP>:9092`）。
⚠️ **所有口令限 hex 字符集**（`openssl rand -hex`；Redis `--user` ACL 参数按空格分词，含空格/
特殊字符会破坏口令解析；MinIO root 口令 ≥8 位）。

## 5. 数据面机取代码 ⭐

```sh
mkdir -p /opt/acme/src && cd /opt/acme/src
git clone <仓库地址> && git checkout acme-foundation   # 受保护主线（main 为历史归档分支，不使用）
```

## 6. 本机构建（CI/CD 本地化）

```sh
sh workspace/env-stack/scripts/local-build.sh
# 产物：.ai-tmp/build/prod-<sha7>/（backend 四 jar + frontend web dist + 五镜像 tar + meta.env）
# 前置：Docker；Maven（不在 PATH 时 export MVN_CMD=完整路径）；Node≥22.13 + pnpm11.8
# build 阶段零凭证；本地打包 skipTests，测试门禁由 ci.yml 承担
```

## 7. 部署与验证（本机一条命令）

```sh
# 本机 export：REGISTRY/REGISTRY_USERNAME/REGISTRY_PASSWORD（或 .ai-tmp/registry.env）
#              ACME_DEPLOY_HOST=<数据面机>   ACME_SRV_HOST=<应用面机>
sh workspace/env-stack/scripts/local-deploy.sh
# 链路：五镜像 push TCR → scp env-stack → SSH 数据面 deploy.sh prod（部署+种子 Nacos+等待 rollout+
#       生成中间件端点文件）→ healthcheck.sh prod → 同步端点+dev.env 到应用面机（dev-tunnel 消费）
```

服务器人工复核：`k3s kubectl -n acme-prod get pods`（postgres/redis/nacos 应落在数据面节点，
五件套落在应用面节点——nodeSelector 钉死）。

**prod 公网入口（三通道，2026-11-19）**：① `http://acmecloud.site`（域名直连，**备案审核/接入
生效中，间歇被 DNSPod 拦截页 302 webblock**——生效后自动恢复，持续 302 就去控制台核对备案状态）；
② 兜底 **`http://124.223.118.235` / `http://43.143.83.112`**（纯 IP 直访，Host=IP 不触发域名拦截，
**200 稳定**——Ingress 已改为无 host catch-all，IP 兜底与域名访问共存）；③ hosts 文件改绑
`<IP> acmecloud.site`（Host 仍为域名，备案未生效期会被拦，不推荐）。原 cloudflared 出站隧道方案
已废弃（ADR 2026-11-14-27 退役）。健康检查不受 DNS 影响（服务器本机 Host 头直探节点 80）。
TLS(443)：证书配置为后续可选项。

## 8. 日常运维要点

- **看状态**：`sh scripts/status.sh prod`（数据面执行；Pod/镜像版本/资源/探活一屏总览）
- **看日志**：`k3s kubectl -n acme-prod logs -f deploy/platform-system`（中间件在数据面、服务在应用面）
- **回滚**：本机 `ACME_ART_DIR=.ai-tmp/build/<历史tag目录> sh scripts/local-deploy.sh`（产物 tar 复用，
  不需重新构建），或数据面 `kubectl rollout undo`
- **释放内存**：`kubectl -n acme-prod scale deployment --all --replicas=0`（中间件 StatefulSet 不缩）
- **dev namespace 播种**：数据面 `sh scripts/seed-nacos.sh dev`（首搭手动一次；prod 由 deploy 自动）
- **Redis 排障**：云端 Redis 无默认用户——`redis-cli --user acme_prod -a "$REDIS_PASSWORD"`；
  dev 凭证连 DB1（`-n 1` / 连接串 database=1）

## 9. 本地联调云端中间件（dev-tunnel v2）

```sh
export ACME_SRV_HOST=<应用面机IP>   # dev-tunnel 对端 = 应用面机（非数据面！）
sh workspace/env-stack/scripts/dev-tunnel.sh dev    # test 为 dev 别名
```

- 纯 `ssh -L` 直达中间件 ClusterIP（端点文件由 local-deploy.sh 同步）：本机
  `15432→PG` / `16379→Redis` / `18848→Nacos HTTP` / `19848→Nacos gRPC`；**无远端进程，断连即拆**
  （旧版 kubectl port-forward 的孤儿清理机制已删除）。
- 脚本打印 `dev.env` 凭证 export 行（PG_DB=acme_dev / PG_USER=acme_dev / REDIS_USERNAME=acme_dev /
  REDIS_DB=1 / NACOS_NAMESPACE=dev / ACME_ENV_ID=dev + 各密码与 HMAC/种子键）——**敏感输出由用户
  终端承接，不得经 AI 工具运行或转录**。
- **后端**：export 凭证后 `PG_HOST=127.0.0.1 PG_PORT=15432 REDIS_HOST=127.0.0.1 REDIS_PORT=16379
  NACOS_ADDR=http://127.0.0.1:18848 mvn ... spring-boot:run`（完整示例见脚本输出）
- **前端**：网关本地起——`WEB_API_TARGET=http://127.0.0.1:9888 pnpm dev`（预览一律 localhost）
- 本地服务注册进 Nacos **dev namespace**，prod 网关（prod namespace）不可见——旧「网关 lb 可能
  路由到本地实例」的调试期泄漏已根治；同 namespace 内多开发者本地实例互相可见（注册同名服务）
  属预期行为。
- **prod 禁用**（只读红线，脚本代码级拦截）；prod 凭证只存数据面机，应用面机仅 dev.env。

## 10. 与 CI 门禁衔接

`ci.yml`（GitHub Actions）保留：全分支 push 触发 ai-config 门禁，主线直推与 PR 触发 app-quality；
`acme-foundation` 与 `ai-coding-support` 必须同一条命令推送（mirror-sync 强制）。构建发布已本地化
（第 6/7 步），GitHub 仅作代码托管与门禁——Gitee/GitHub 双推不变。
