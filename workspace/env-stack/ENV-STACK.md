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

- 环境三态对齐 `.ai/env/{dev,test,prod}.yaml`：本目录负责**搭**，`.ai/env/` 负责**连**（MCP）。
- 部署契约：`cd.yml` → `scripts/deploy.sh <env>` → `kubectl apply -k k8s/overlays/<env>` →
  `scripts/healthcheck.sh <env>`；镜像地址由 deploy.sh 经 `kustomize edit set image` 注入。
- 真实凭证不入库：模板一律占位；服务器侧放 `/opt/acme/<env>/{app.env,registry.env}`，
  由 deploy.sh 注入为集群 Secret（`acme-app-secrets` / `acme-registry-auth`）。
- 4G 单机资源纪律：全部工作负载设内存 limit；**同一时间只常驻一个环境**（见 RUNBOOK 第 8 节）。
- 构建产物、容器卷、下载解压物放根目录 `.ai-tmp/`，不放本目录。
- 每个脚本入口写清用法注释（`# 用法：…`），AI 与人都能直接执行。
