# env-stack/ — 运行环境搭建

存放「把整套环境跑起来」所需的一切：容器编排、初始化脚本、环境配置模板。目标：新机器/新协作者
**只按本目录**就能搭出两台云机的分层部署环境（决策 `2026-11-18-02`：两台 4C4G 分层机型 + 单套中间件
两环境逻辑隔离 prod/dev，test 并入 dev；`2026-11-18-03`：CI/CD 本地化）。

## 骨架

```text
env-stack/
├── ENV-STACK.md   # 本文件：约定说明
├── RUNBOOK.md     # 两台 4C4G 云机（两层机型 · k3s 两节点）从零到部署的操作手册
├── k8s/           # Kustomize 清单：base（环境无关，中间件 nodeSelector 留 overlay 钉位）
│                  #   + overlays/{prod,monolith}（dev/test 云端已退役——本地服务经 dev-tunnel 接中间件）
├── scripts/       # local-build.sh / local-deploy.sh（本机 CI/CD）/ deploy.sh / healthcheck.sh /
│                  #   seed-nacos.sh / status.sh / dev-tunnel.sh（deploy 幂等，失败非零退出）
└── config/        # 凭证模板 *.example.env（真实值数据面机 /opt/acme/prod/ 与本机 .ai-tmp/，不入库）
```

编排选型为 **k3s 两节点（一 server 一 agent）+ Kustomize**（`2026-11-18-02`，取代单机方案）：
数据面机 = k3s server + 单套中间件（nodeSelector `acme/role=env`）+ Traefik 入口 + prod 备份；
应用面机 = k3s agent + prod 五件套（`acme/role=app`）。同一 k3s 集群，ClusterIP 跨节点路由；
两台为轻量应用服务器（跨账号），组网走**公网 IP + wireguard-native 加密后端**（节点全注册公网 IP）。
应用镜像 Dockerfile 不在本目录：后端 `workspace/apps/backend/Dockerfile`（自包含多阶段，保留）与
`Dockerfile.prebuilt`（消费 local-build.sh 产物）、前端 `Dockerfile.web` / `Dockerfile.web.prebuilt`。

## 约定

- 云端常驻仅 **prod**（namespace `acme-prod`）：本地 dev/test 服务一律本地跑，经 `dev-tunnel.sh`
  连中间件 dev 隔离面（凭证 `dev.env`，test 并入 dev 同一套）；本目录负责**搭**，`.ai/env/` 负责
  **连**（MCP）与运行环境定义。
- 部署契约（本地化）：`local-build.sh`（本机产物 → `.ai-tmp/build/prod-<sha7>/`）→
  `local-deploy.sh`（push TCR → SSH 数据面 `deploy.sh prod` → `healthcheck.sh prod` → 端点与 dev.env
  同步应用面机）；镜像地址由 deploy.sh 在临时目录向 overlay kustomization 追加 `images` 段注入
  （kubectl 内置 kustomize 渲染，不依赖独立 kustomize 二进制、不弄脏仓库文件）。
- 真实凭证不入库：模板一律占位；数据面机放 `/opt/acme/prod/{app.env,registry.env}`
  （app.env 含双环境键 `PG_DEV_PASSWORD`/`REDIS_DEV_PASSWORD`），由 deploy.sh 注入为集群 Secret
  （`acme-app-secrets` / `acme-registry-auth`）；应用面机仅 `/opt/acme/tunnel/dev.env`（dev 凭证，
  600）与 `/opt/acme/mw-endpoints.env`（ClusterIP 端点，**无凭证**）。
- 中间件两环境隔离：PG 双库（acme_prod/acme_dev + dev 独立用户）、Nacos 双 namespace（seed-nacos.sh
  建 namespace + 播种）、Redis 双 ACL 用户 + DB index（prod=0/dev=1）+ 默认用户关闭——键隔离靠
  DB index、pub/sub 靠 KeyComposer 环境段（`ACME_ENV_ID`），二者互补（ADR 2026-11-18-02）。
- 资源纪律：全部工作负载设内存 limit；中间件常驻数据面（不动），五件套常驻应用面。
- 构建产物、容器卷、下载解压物放根目录 `.ai-tmp/`，不放本目录。
- 每个脚本入口写清用法注释（`# 用法：…`），AI 与人都能直接执行。

## 对象存储（REQ-20261117-045754）

> ✅ **2026-11-19 已启用并实测**：MinIO 部署于数据面（1/1），桶 `acme-prod` 已建（mc 回环验证），
> prod 经 Nacos data-id 切 `acme.storage.type=minio`（endpoint 为公网同源地址，/object-storage/
> 同源反代见 web/nginx.conf）；dev/test 并入 dev 保持 LOCAL 落盘。状态详见
> `.ai-memory/playbooks/two-tier-deployment.md`。

### MinIO 清单（k8s/base/minio.yaml）

- `StatefulSet minio`（镜像 `minio/minio:RELEASE.2024-01-31T20-20-33Z`，与 framework-storage
  Testcontainers 测试同 tag）+ `Service minio:9000`（S3 API）+ PVC 2Gi（volumeClaimTemplates，
  回收策略随 k3s 缺省 StorageClass 为 Delete，删 PVC 前先缩容）。
- 凭据经集群 Secret `acme-app-secrets` 注入（`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`）：
  在服务器 `/opt/acme/<env>/app.env` 增加两键后由 deploy.sh 创建/更新；缺键时凭据 env 解析失败，
  属预期防误开。桶不自动创建：启用后先手动建桶（`mc alias set` + `mc mb`），桶名与
  `acme.storage.bucket`（缺省 `acme`）一致。
- `scripts/deploy.sh` 对 `statefulset/minio` 按存在性判断后再等待（未部署环境跳过，避免 rollout 超时
  阻断部署）。

### `acme.storage.*` 全键表（StorageProperties，platform-infra）

| 键 | 缺省 | 说明 |
|---|---|---|
| `acme.storage.enabled` | `true` | 模块开关（false 时 Properties/门面 bean 一并消失） |
| `acme.storage.type` | `local` | 适配器类型：`local` / `minio` / `oss` |
| `acme.storage.endpoint` | 空 | minio/oss 服务端点（同源反代时配公网同源地址，见下） |
| `acme.storage.access-key` / `secret-key` | 空 | 凭证（env 注入，不入库；缺失启动显式失败 SYS-15-0003） |
| `acme.storage.bucket` | `acme` | 桶名 |
| `acme.storage.presign-ttl` | `15m` | 预签名有效期（下载 url 与直传 PUT 预签名同源口径） |
| `acme.storage.connect-timeout` / `read-timeout` | `5s` / `30s` | 连接/读取超时 |
| `acme.storage.url-mode` | `presign` | 访问 URL 模式：`presign` / `public` |
| `acme.storage.local.root-dir` | 空（必配） | LOCAL 落盘根目录（缺失启动显式失败 SYS-15-0006） |
| `acme.storage.local.url-base` | 空 | LOCAL 访问 URL 前缀（空=仅返回对象相对路径） |
| `acme.storage.upload.max-size` | `10MB` | 单文件大小上限（超限 FILE_SIZE_EXCEEDED） |
| `acme.storage.upload.allowed-content-types` | `xlsx,pdf,png,jpg,jpeg,txt,csv` | 类型白名单（扩展名或 MIME 双口径） |
| `acme.storage.upload.retention-days` / `retention-cron` | `7` / `0 0 3 * * *` | 软删保留期与清理 cron |
| `acme.storage.upload.max-image-px` | `4096` | 图片像素上限（image/* 可解码且宽高 ≤ 上限，REQ-20261117-045754 确认④） |
| `acme.storage.quota.default-bytes` | `1073741824` | 单租户配额字节（0=不限；口径=未删行 size 总和＋本次，并发下为近似护栏，确认③） |

### LOCAL / MINIO 切换（Nacos data-id，代码零改动）

platform-infra 经 `spring.config.import: nacos:platform-infra.yaml` 接配置中心——`acme.storage.*`
键在该 data-id 内修改后重启生效（`scripts/seed-nacos.sh` 维护种子）：

```yaml
acme:
  storage:
    type: minio                      # local → minio
    endpoint: http://minio:9000      # 直连集群内 Service；同源直传场景配公网同源地址（见下）
    access-key: ${ACME_STORAGE_ACCESS_KEY}   # 凭证走环境变量注入（app.env），不入 Nacos 明文
    secret-key: ${ACME_STORAGE_SECRET_KEY}
    bucket: acme
```

LOCAL 与 MINIO 两种模式下上传三端点（multipart `POST /admin/v1/infra/files` 与直传
`direct-upload-preparations` → PUT → `direct-upload-completions`）行为一致：LOCAL 走服务端一次性
票据 PUT 端点（同源同认证面），MINIO/OSS 直传 PUT 走预签名 URL——浏览器 CSP `connect-src 'self'`
下必须同源，见下节。

### 同源反代样例（MINIO 直传启用前提）

web 的 CSP `connect-src 'self'` 仅允许同源连接，预签名 URL 须落在同源域名下：nginx 保留 Host 转发
（签名含 Host 头），`acme.storage.endpoint` 配公网同源地址（含反代前缀）。样例（注释形态，
`apps/frontend/web/nginx.conf` 同款未启用）：

```nginx
# /object-storage/ → minio:9000（前缀剥离：proxy_pass 带尾斜杠）
# location /object-storage/ {
#     proxy_pass http://minio:9000/;
#     proxy_set_header Host $host;            # 预签名含 Host 头，必须保留原值
#     proxy_set_header X-Real-IP $remote_addr;
#     proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
#     proxy_set_header X-Forwarded-Proto $scheme;
#     client_max_body_size 12m;               # 与 spring.servlet.multipart 上限对齐
# }
```

对应配置：`acme.storage.endpoint=https://acmecloud.site/object-storage`（预签名 URL 形如
`https://acmecloud.site/object-storage/acme/files/...?X-Amz-Signature=...`，PUT 时客户端按
preparation 返回的 headers 原样携带 Content-Type 头）。


## 双形态：分布式（默认）与单体打包（REQ-20261118-002130，交付不部署）

分布式为默认形态（本目录既有编排：gateway + 三服务 + web + 中间件）。单体为可选打包路径：
单 JVM 聚合三服务（镜像 `acme/acme-monolith`，端口 9680，无 Nacos/Kafka 运行面），面向本地
联调 / 小部署。两者共用同一批中间件与 `acme-app-config` / `acme-app-secrets`。

### 构建与镜像

- 后端 Dockerfile 增 `ARG PACKAGE=module`（module=现行为按 `MODULE_PATH` 构建；bootstrap=单体
  形态标记，等价于 `MODULE_PATH=acme-bootstrap` 的普通模块构建，实现取简）。
- ~~`cd.yml` dispatch/call 增输入 `package_mode`~~（2026-11-19 cd.yml 随 CI/CD 本地化退役）：
  单体构建为手动路径——`docker build --build-arg PACKAGE=bootstrap --build-arg
  MODULE_PATH=acme-bootstrap`（镜像 `acme/acme-monolith:<tag>`，跳过四服务；单体部署编排属
  后续可选；deploy.sh 清单驱动面向分布式五件套）。
- 三服务镜像构建取用注意（REQ-20261118-002130）：三 `*-service` 的可执行 jar 改为
  `-exec` 分类器附加（主构件保持普通 jar 供 `acme-bootstrap` 单体聚合依赖——Boot 重打包的
  BOOT-INF 布局不可作为依赖 jar 复用）；Dockerfile 构建步骤优先取 `*-exec.jar`，
  取用行为对镜像产物不变。

### monolith overlay（k8s/overlays/monolith）

- `overlays/monolith/kustomization.yaml` + 同目录 `acme-monolith.yaml`（Deployment + Service
  9680，镜像占位 `acme/acme-monolith:latest`）。manifest 落位说明：契约设想为
  `k8s/base/acme-monolith.yaml`，因 kustomize root-only 加载器禁止跨根单文件引用（目录级
  上溯引用才放行），落位在 overlay 内——base kustomization 不登记，分布式环境零影响。
- 资源面：`../../base` 取 postgres/redis/nacos/web/ingress + acme-monolith；JSON6902 delete
  patch 剔除 acme-gateway 与三服务部署单元；ingress `/ws` 后端由 acme-gateway:9888 改指
  acme-monolith:9680（WS 升级透传直连单体）。
- 启用步骤（未做部署冒烟，属后续可选）：① 服务器 app.env 就位（双 HMAC + 三种子密码 +
  `ACME_STORAGE_LOCAL_ROOT_DIR`，键与分布式同源）→ ② 镜像注入：把 `images` 段换成
  `newName: <REGISTRY>/acme-monolith, newTag: <env>-<sha7>`（deploy.sh 同型手法）→
  ③ `kubectl apply -k k8s/overlays/monolith`。渲染验证（AC-9）：
  `kubectl kustomize k8s/overlays/monolith`（无集群面时 `--dry-run=client` 需 API openapi，
  纯渲染用 kustomize）。
- 能力差异提示：web 的 nginx 反代仍指向网关（nginx.conf 打进镜像）——单体形态下
  `/admin/`、`/app/` 流量经 web 反代需改其 upstream 指向 `acme-monolith:9680` 后重建 web
  镜像，或直接经 ingress/直连单体端口访问；能力差异全表见 `apps/backend/BACKEND.md`「双形态」。
