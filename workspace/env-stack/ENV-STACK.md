# env-stack/ — 运行环境搭建

存放「把整套环境跑起来」所需的一切：容器编排、初始化脚本、环境配置模板。目标：新机器/新协作者**只按本目录**就能从零搭出 dev（及 test）全套运行环境。

## 骨架

```text
env-stack/
├── ENV-STACK.md        # 本文件：约定说明
├── docker/            # 容器编排：按环境分子目录（dev/ 已落地，test/ 待建）
│   └── dev/           #   compose.dev.yml + 一组件一目录 + .env.example
├── scripts/           # 初始化/引导脚本（bootstrap、seed、reset 等）
└── config/            # 环境配置模板（*.example 或 ${VAR} 占位；真实配置不入库）
```

## 约定

- 环境三态对齐 `.ai/env/{dev,test,prod}.yaml`：本目录负责**搭**，`.ai/env/` 负责**连**（MCP）。
- 真实凭证不入库：模板一律 `${VAR}` 占位，配合仓库根 `.env` 或 `docker/dev/.env`（均已 gitignore）使用。
- 构建产物、容器卷、下载解压物放根目录 `.ai-tmp/`，不放本目录。
- 每个脚本入口写清用法注释（`# 用法：…`），AI 与人都能直接执行。

## 开发环境（已落地）

入口 [docker/dev/compose.dev.yml](docker/dev/compose.dev.yml)（仅聚合 `include`，组件定义在各自子目录）。

```bash
# 首次：建本地环境变量（.env 不入库）
cp env-stack/docker/dev/.env.example env-stack/docker/dev/.env

# 起栈 / 就绪判断 / 停止
docker compose -f env-stack/docker/dev/compose.dev.yml up -d
docker compose -f env-stack/docker/dev/compose.dev.yml ps        # 各组件须为 healthy
docker compose -f env-stack/docker/dev/compose.dev.yml down -v    # 停并清数据（慎用）
```

组件、端口、MCP 接入与新增组件步骤见 [docker/dev/README.md](docker/dev/README.md)。

## 测试 / 生产（待建）

- `docker/test/`：对齐 `.ai/env/test.yaml`，同结构（`compose.test.yml` + 一组件一目录）。
- 生产不由本目录编排（见 Backend.md 第 15 章口径），`scripts/deploy.sh` 由 `.ai/ci/cd.yml` 调用，当前为显式失败占位。
