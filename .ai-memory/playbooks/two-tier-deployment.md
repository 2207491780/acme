# 两层机型 + 单套中间件实施手册（two-tier-deployment）

> 决策与取舍见 `../decisions/2026-11-18-02-two-tier-single-middleware.md`。
> 适用任务：两台云机部署形态改造、单套中间件三环境隔离、dev/test 云端退役、dev-tunnel v2。

## 前置（腾讯云控制台，用户侧操作）

1. **机器形态（2026-11-18 实测定稿）**：两台为**轻量应用服务器（Lighthouse）**、分属不同账号——
   轻量机不走 CVM 私有网络控制台（VPC 列表为空属预期），跨账号无内网互通。组网走**公网 IP +
   k3s wireguard-native 加密后端**：6443/10250 本身 TLS，跨机封装流量走 wireguard（UDP 51820）。
   **禁止 VXLAN 等明文协议跨公网**（ADR 2026-11-18-02 例外修订条目）。
2. 轻量防火墙规则（防火墙页 → 添加规则 → 应用类型「自定义」，来源 = **对方公网 IP**）：

   | 机器 | 入站放行 | 用途 |
   |---|---|---|
   | 数据面 | TCP 6443、UDP 51820、TCP 10250 ← 应用面公网 IP | agent join + API Server / wireguard / kubelet |
   | 应用面 | UDP 51820、TCP 10250 ← 数据面公网 IP | wireguard / kubelet |
   | 公网面 | 数据面 22(管理员；部署经本机 SSH)/80/443；应用面 22(开发者+管理员) | 80/443 只开数据面 |

3. k3s 安装：server 加 `--flannel-backend=wireguard-native --node-ip=<内网>
   --node-external-ip=<公网> --flannel-external-ip`；**agent 不支持 `--flannel-backend` 与
   `--flannel-external-ip`**（实测 fatal）——agent 只加 `--node-ip=<内网> --node-external-ip=<公网>`
   （flannel 公网端点随 node-external-ip 自动发布），join 指向数据面**公网** 6443；装后打节点标签
   `acme/role=env` / `acme/role=app`。两台装完都要 `/etc/rancher/k3s/registries.yaml`（docker.io →
   mirror.ccs.tencentyun.com），否则国内拉不到 pause 沙箱镜像。
4. 验证：应用面测数据面**公网** `6443/TCP` 通；`kubectl get nodes` 双 Ready；跨节点 pod 互通冒烟
   （两节点各起一个 pod 互 ping，流量经 wireguard 隧道，同地域实测 RTT ≈2–3ms）。
   **API endpoint 陷阱（2026-11-19 实测）**：server 须加 `--advertise-address=<数据面内网 IP>`，
   否则 `kubernetes` Service endpoint 会注册成公网 IP，而轻量机 NAT 回环不通 → 所有 pod 连
   `10.43.0.1` 全超时（coredns/local-path/helm-install/metrics 全灭）。API 依赖型组件
   （coredns/local-path-provisioner/traefik）须 patch nodeSelector 钉数据面；`--disable metrics-server`
   关闭可选组件；**agent 不支持 `--flannel-backend` / `--flannel-external-ip`**（flannel 公网端点随
   agent 的 `--node-external-ip` 自动发布）。Redis ACL 的 `--user` 参数必须按空格**分词**传参
   （整串引号包裹会被当成含空格用户名直接退出）。
5. 防呆：控制台实例命名可能与角色相反（以仓库根 `cloud-servers.yml` 的 data-plane/app-plane 为
   角色权威，勿据命名判断）；镜像拉取走公网（TCR），留意轻量套餐流量包余量（≈1.5GB/次构建）。
5. **prod 公网入口（2026-11-19 已生效）**：`acmecloud.site` 备案通过、A 记录绑定应用面机公网 IP，
   `http://acmecloud.site` 直连可用；cloudflared 出站隧道方案**废弃**（ADR 2026-11-14-27 退役）。
   注意：`healthcheck.sh` 探活是在服务器本机以 Host 头直探节点 80（无 DNS、不过公网边缘），**不受
   备案拦截影响**，勿「修复」。TLS(443) 证书为后续可选项。

## 云机访问认证纪律（2026-11-18 用户定向，规则已挂结论）

- **仅密码认证，永久禁止安装公钥/密钥登录**（规则 `.ai/rules/ai-guidelines.md` 安全红线）。
- 本机工具链（`.ai-tmp/`，gitignore 不入库）：`load-servers.sh`（从仓库根 `cloud-servers.yml`
  字段提取两机连接参数，**绝不回显**）+ `ssh-role.sh <dp|ap> '<命令>'`（密码经 OpenSSH
  `SSH_ASKPASS_REQUIRE=force` 注入，口令不出现在命令行/进程参数/对话转录）。
- sudo 场景（非 root 用户）：口令经 stdin 管道传入 `sudo -S`（不进远程进程参数）；root 直登无此
  需求。
- 重装机指纹变更：`ssh-keygen -R <host>` 清旧指纹后重连（用户确认重装属预期，非 MITM）。
- `dev-tunnel.sh` / `local-deploy.sh` 等正式脚本目前仍按密钥路径设计——如需它们走密码认证，改造
  口径同上（SSH_ASKPASS），不改认证方式本身。

## 文件与凭证

- 仓库根 `cloud-servers.yml`（gitignore 不入库）：data-plane / app-plane 的 host/port/user/key。
- 数据面 `/opt/acme/prod/{app.env,registry.env}`：prod 全量凭证（含 3 个种子密码键 + 双环境中间件
  侧键 `PG_DEV_PASSWORD` / `REDIS_DEV_PASSWORD`），**仅存此机**。
- 应用面 `/opt/acme/tunnel/dev.env`：dev 凭证（600 权限；test 已并入 dev，同一套），由部署链路同步
  （CI scp 两台，或数据面→应用面 SSH key 推送，实现取简），**不含 prod 值**。

## 实施步骤（每步独立提交、提交前全量测试）

1. **framework 键/通道环境段**：`framework-core/KeyComposer` 加可选环境段（属性 `acme.env-id`，系统
   环境变量 `ACME_ENV_ID` 松散绑定；空 = 输出与历史格式逐字符一致）；`framework-cache/L1InvalidationBus.channelOf`
   加 env 段重载并经 `keyComposer.envSegment()` 同源取值（`NotifyPushBus` 通道经 compose 自动隔离，
   无需改动）；回归测试：设/不设环境段、含冒号 fail-fast、Environment 构造解析。验收：framework-core
   + framework-cache `mvn test`。
2. **服务 yml 占位**：gateway / 三 `*-service`（及 acme-bootstrap）application.yml 加
   `${NACOS_NAMESPACE:}`、`${REDIS_DB:0}`（spring.data.redis.database）与 `${REDIS_USERNAME:}`
  （Redis ACL 用户名）；零注入时行为不变。
3. **k8s 清单（已完成）**：base 中间件双环境改造（PG init 建双库 acme_prod/acme_dev + dev 独立用户
   acme_dev，PG limit 512Mi；Redis 7 ACL 双用户 acme_prod/acme_dev——独立用户名/密码 + DB index
   prod=0/dev=1 + pub/sub 通道 `&` 前缀，默认用户关闭，limit 128Mi；seed-nacos.sh 建 namespace
   （prod/dev，test 别名并入 dev）+ 播种 + 防呆）；prod overlay nodeSelector 两层（middleware 批
   `acme/role: env` ×3 StatefulSet，服务/web 批 `acme/role: app` ×5 Deployment——web 实名
   `acme-web`）+ configMap merge 注入 `ACME_ENV_ID=prod / NACOS_NAMESPACE=prod / REDIS_DB=0 /
   PG_DB=acme_prod / PG_USER=acme / REDIS_USERNAME=acme_prod`（PG_USERNAME 保留为 postgres
   superuser 名）；prod LOCAL 存储 PVC 启用（沿用 dev 实测模式，MinIO 备选注释保留）；
   dev/test overlay 目录已退役。验收：`kubectl kustomize overlays/prod` 渲染通过、8/8 nodeSelector、
   注入键齐备。
4. **脚本（进行中）**：`deploy.sh` 增中间件端点文件生成（PG/Redis/Nacos ClusterIP，kubectl 解析）并
   随部署链路同步端点文件 + `dev.env` 凭证文件到应用面机（凭证键 = PG_PASSWORD→PG_DEV_PASSWORD 口径
   分流 + PG_DB=acme_dev/PG_USER=acme_dev/REDIS_USERNAME=acme_dev/REDIS_PASSWORD=<dev 口令>/
   REDIS_DB=1/NACOS_NAMESPACE=dev/ACME_ENV_ID=dev + 双 HMAC + 三种子密码）；`healthcheck.sh` 适配；
   `dev-tunnel.sh` v2：SSH 连 **应用面机**，纯 `ssh -L` 转发 PG(15432)/Redis(16379)/Nacos
   HTTP(18848)+gRPC(19848) 到中间件 ClusterIP（运行时读端点文件，不解析 DNS、不用
   kubectl port-forward），无远端进程（孤儿清理段删除），参数仅 `dev`（`test` 别名并入），凭证
   export 行改读应用面机文件，prod 依旧代码级拦截。
5. **本地 CI/CD 链路（决策 `../decisions/2026-11-18-03-ci-build-local.md`）**：`.ai/ci/cd.yml` 与派生
   `.github/workflows/cd.yml` 退役（`ci.yml` 门禁保留）；构建/部署拆两步、以产物目录为接口：
   `local-build.sh` 产物落 `.ai-tmp/build/<env>-<sha7>/`（四可执行 jar + web dist + 五镜像 tar +
   `meta.env`，**零凭证**；Maven/pnpm 工具链本机化，`Dockerfile(.web).prebuilt` 轻量 COPY 打包）；
   `local-deploy.sh` 读 meta → 镜像缺失 `docker load` ← tar（跨目录/跨机复用）→ `docker tag`/push
   TCR（凭证 `.ai-tmp/tcr.env` 或环境变量）→ scp env-stack → SSH 数据面机 `deploy.sh prod` +
   `healthcheck.sh prod`（`ACME_SRV_*` 对照 cloud-servers.yml）。
6. **文档**：RUNBOOK（两节点组网/本地构建部署链路/运维要点，第 6/7/9 步重写）、ENV-STACK（拓扑/单套
   中间件/overlay 与 cd.yml 退役/子域退役）、BACKEND.md（键环境段口径）、CI-GUIDE（cd 部分退役、
   TCR 凭证本机化）、conversational-cd（触发方式改为本地脚本）、`.ai/env/cloud-{dev,test}.yaml`
   notes（隧道对端 = 应用面机）。

## 旧方案退役（用户侧操作，按序执行）

1. **先立新再拆旧**：备案未下期间 dev 的 cloudflared 隧道是当前唯一可用公网入口——新 prod 公网入口
   （数据面 cloudflared 挂根域，或备案后直连）就绪之前，不得删除旧隧道/旧 DNS，否则全站断网。
2. Cloudflare 控制台删 dev/test 子域记录（dev CNAME→cfargotunnel、test A 记录）；根域保留待指数据面机。
3. 旧 dev 服务器退役时：删集群 secret `cloudflared-config` 与 cloudflared 部署（处置方向见「开放问题」）。
4. ~~GitHub 侧 Environments~~（cd.yml 退役后不需要）；~~TCR dev/test 旧镜像~~；~~cloudflared 出站
   隧道~~（2026-11-19 备案通过、直连生效，27 号方案退役）。
5. 仓库侧退役已随实施落地：dev/test overlay 目录删除、cd.yml 收敛 prod-only（dispatch 去 env 入参、
   workflow_call 移除，见 .ai/ci/cd.yml）。
