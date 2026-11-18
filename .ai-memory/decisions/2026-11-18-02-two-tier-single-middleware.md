# 两台 4C4G 云机部署形态：两层机型 + 单套中间件两环境逻辑隔离

> 取代 `2026-11-13-06-k8s-k3s-single-node.md` 的「k3s 单节点」与「单机单环境互斥常驻」结论；
> 其 k3s 选型、base+overlays 清单、脚本契约、服务器不构建等结论继续有效。
> DNS 衔接 `2026-11-14-25-domain-routing.md` 与 `2026-11-14-27-dev-domain-cloudflare-tunnel.md`：
> **prod 公网入口已于 2026-11-19 直连生效**——`acmecloud.site` 备案通过并绑定应用面机公网 IP，
> 腾讯云拦截解除，cloudflared 出站隧道方案**废弃**（27 号决策退役）；dev/test 子域云端入口退役。
> 入口流量路径：DNS → 应用面:80（svclb hostPort）→ Traefik → 网关/服务；TLS(443) 证书待后续配置。
> 实施步骤见 `.ai-memory/playbooks/two-tier-deployment.md`。

## 背景

用户名下两台腾讯云 4C4G/60GB 云机（机器信息在仓库根 `cloud-servers.yml`，gitignore 不入库），承接现有
单机 k3s 部署演进（dev 全栈已在单 4G 机验证运行：4 JVM + Nacos/PG/Redis/web，limits 合计 ≈3Gi +
k3s ≈1Gi，2G swap 兜底）。演进诉求：① 两台分工并支撑后期「中间件与服务膨大、新增」；② 中间件保持
单实例、不按环境翻倍；③ 环境可区分（prod 与本地 dev/test 数据隔离）；④ 本地隧道连应用面机（开发者
SSH 不落数据面机）。**2026-11-18 晚间实测修正**：两台为腾讯云**轻量应用服务器（Lighthouse）**且分属
不同账号——轻量机网络不走 CVM 私有网络控制台、跨账号无内网互通（内网路由实测不通），VPC/对等连接
路线整体不可行；组网改走**公网 IP + k3s wireguard-native 加密后端**（6443/10250 本身 TLS，VXLAN 换
wireguard UDP 51820 加密隧道），轻量防火墙以源 IP 限对方公网地址。

## 决定

- **两层机型（一个 k3s 集群跨两节点）**：数据面机 = k3s server，承载单套中间件（PG/Redis/Nacos，
  启用后 MinIO）、Traefik 入口（80/443 公网）、prod 备份（pg_dump cron）；应用面机 = k3s agent，承载
  prod 五件套（gateway/三服务/web）。nodeSelector 两层调度（`acme/role=env` / `acme/role=app`）钉死
  落位；中间件与服务**同命名空间**（ClusterIP 跨节点路由，`PG_HOST=postgres` 等配置键零改动，不引入
  跨 namespace FQDN 改造）。
- **单套中间件承载两环境逻辑隔离（零边际内存；2026-11-18 用户定向：test 并入 dev）**：PG 一实例
  双库（acme_prod / acme_dev——prod 沿用 superuser 连接、dev 独立用户 acme_dev）；Nacos 一实例双
  namespace（prod/dev，seed-nacos.sh 建 namespace + 播种 + 防呆）；Redis 一实例双 ACL 用户
  （独立用户名/密码 + DB index prod=0 / dev=1 + pub/sub 通道 `&` 前缀三重限制）+ 键/通道环境段
  （`KeyComposer` 读 `acme.env-id`，`L1InvalidationBus` 通道同源改造；`NotifyPushBus` 经 compose
  自动隔离）；MinIO（启用后）一实例双桶。双硬要求互补缺一不可：DB index 管键（网关撤销集
  `auth:revoke:{jti}` 是字面键、不经 KeyComposer），环境段管 Pub/Sub（通道实例级全局、不受
  DB index 约束；NotifyPushBus 载荷直达 WS 会话，串扰即数据泄漏）。
- **dev/test 云端退役、本地运行**：dev/test 服务一律本地跑（`mvn spring-boot:run` / `pnpm dev`），经
  dev-tunnel v2 连数据面机中间件（dev 凭证，test 并入 dev 同一套）；云端只保留 prod overlay 与
  prod 部署；dev/test overlay 退役、cd.yml 退役（CI/CD 本地化，见
  `2026-11-18-03-ci-build-local.md`）；dev./test. 子域不再提供云端入口（预览走 localhost）。
- **dev-tunnel v2 连应用面机（用户定向）**：纯 `ssh -L` 直连中间件 ClusterIP（数据面 kubectl 解析端点
  文件、部署链路同步到应用面机，脚本运行时读文件不解析 DNS）；无远端常驻进程（现有孤儿清理机制整段
  删除）；凭证分流：dev/test 凭证文件放应用面机（600 权限），prod 凭证只存数据面机 `/opt/acme/prod/`。
  公网腿流量与直连数据面等量（不省流量，省的是数据面 SSH 暴露面——数据面 22 收紧至管理员，部署经
  本机 SSH，CI 专用部署密钥随 cd.yml 退役）。
- **增长路径**：新增中间件 → 数据面机加 StatefulSet + 每环境隔离配置（不按环境翻倍）；新增服务 →
  应用面机加 Deployment + Nacos 注册 + 网关路由；内存/数据撞墙 → 数据面机原地升配（数据面升配不动
  应用），PG 数据量大了可 dump/restore 迁出独立专机。
- **残余风险（接受并记录）**：Nacos/PG/Redis 实例级单点为两环境共享（namespace/库隔离数据，不隔离
  实例故障）；dev 与 prod 共享实例算力（噪声邻居，dev 用户限连接数 + statement_timeout 缓解）；
  Nacos 社区版 namespace 级 RBAC 不强制（dev 账号经配置仍可写 prod namespace，靠 seed 防呆
  与运维纪律兜底）；**公网组网**——6443/51820/10250 暴露公网（防火墙源限对方 IP + TLS/wireguard
  加密缓解），跨机流量走公网受轻量套餐带宽与流量包约束（镜像拉取 ≈1.5GB/次构建），同地域公网延迟
  ≈1–5ms。

## 被否的选项

- **环境分机（A 机 dev/test 全栈互斥常驻，B 机 prod 全栈）**：零代码改动、对网络互通零要求、每台
  自成一体。否决：用户定向两层机型 + 单套中间件承载多环境（内存余量 ≈1.5Gi vs ≈0.8Gi，新中间件
  不按环境翻倍），并明确旧方案直接废弃、不留回退（2026-11-18）。
- **同一环境跨两节点双副本**：单环境 limits ≈3Gi，双副本必超 4G，且对稳定性无增益。
- **用公网 IP 打通两机组网（VXLAN 明文形态）**：flannel VXLAN 明文封装跨公网——安全不可接受，
  维持否决。**例外修订（2026-11-18）**：轻量机跨账号无内网互通，改用 `--flannel-backend=wireguard-native`
  （UDP 51820 加密隧道）+ 6443/10250 本身 TLS + 防火墙源限对方公网 IP 的公网组网，加密前提成立后
  启用；明文协议跨公网仍一律禁止。
- **两套中间件（acme-prod 与 acme-dev 各一套）**：中间件 limits 合计 ≈2.2Gi 吃掉增长余量
  （仅剩 ≈0.8Gi），且每个新增中间件按环境翻倍。
- **monolith 单体形态省内存**：ENV-STACK 明确交付未部署冒烟、web 反代 upstream 需改镜像重建；
  平稳优先不启用。
- **隧道连数据面机（k3s server）**：用户定向连应用面机；技术上纯 `ssh -L` 到 ClusterIP 两机皆可，
  差异仅多一跳免费内网与数据面 SSH 暴露面。
