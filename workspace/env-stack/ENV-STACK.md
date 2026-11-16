# env-stack/ — 运行环境搭建

存放「把整套环境跑起来」所需的一切：容器编排、初始化脚本、环境配置模板。目标：新机器/新协作者**只按本目录**就能从零搭出 dev（及 test）全套运行环境。

## 骨架

```text
env-stack/
├── ENV-STACK.md   # 本文件：约定说明
├── RUNBOOK.md     # 云服务器（k3s 单机）从零到部署的操作手册
├── k8s/           # Kustomize 清单：base（环境无关）+ overlays/{dev,test,prod}
├── scripts/       # deploy.sh / healthcheck.sh / seed-nacos.sh（deploy 幂等，失败非零退出）
└── config/        # 凭证模板 *.example.env（真实值服务器侧 /opt/acme/<env>/，不入库）
```

编排选型为 **k3s 单机 + Kustomize**（决策 `2026-11-13-06-k8s-k3s-single-node.md`）。
应用镜像的 Dockerfile 不在本目录：后端 `workspace/apps/backend/Dockerfile`（上下文含
acme-parent 兄弟目录）、前端 `workspace/apps/frontend/Dockerfile.web`（构建上下文与
pnpm/Maven workspace 对齐）。

## 约定

- 环境三态对齐 `.ai/env/cloud-{dev,test,prod}.yaml`（`env` 字段仍为 dev/test/prod）：本目录负责**搭**，`.ai/env/` 负责**连**（MCP）与运行环境定义。
- 部署契约：`cd.yml` → `scripts/deploy.sh <env>` → `kubectl apply -k k8s/overlays/<env>` →
  `scripts/healthcheck.sh <env>`；镜像地址由 deploy.sh 在临时目录向 overlay kustomization 追加 `images` 段注入
  （kubectl 内置 kustomize 渲染，不依赖独立 kustomize 二进制、不弄脏仓库文件）。
- 真实凭证不入库：模板一律占位；服务器侧放 `/opt/acme/<env>/{app.env,registry.env}`，
  由 deploy.sh 注入为集群 Secret（`acme-app-secrets` / `acme-registry-auth`）。
- 4G 单机资源纪律：全部工作负载设内存 limit；**同一时间只常驻一个环境**（见 RUNBOOK 第 8 节）。
- 构建产物、容器卷、下载解压物放根目录 `.ai-tmp/`，不放本目录。
- 每个脚本入口写清用法注释（`# 用法：…`），AI 与人都能直接执行。

## 对象存储（REQ-20261117-045754）

> ⚠️ **本小节与 `k8s/base/minio.yaml` 为 REQ-20261117-045754 交付物，未部署未实测**；
> 启用前必须先做数据面验证（建桶 + 上传/下载回环 + 同源反代回环）。当前各环境存储缺省仍为
> LOCAL（本地盘，platform-infra-storage 持久卷）。

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

